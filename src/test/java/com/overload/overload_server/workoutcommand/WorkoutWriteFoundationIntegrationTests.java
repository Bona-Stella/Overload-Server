package com.overload.overload_server.workoutcommand;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.Account;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.AccountSeed;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.exercise.ExerciseDefinitionRepository;
import com.overload.overload_server.security.SessionPrincipal;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import com.overload.testfixture.MixedPersistenceAssertions;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class WorkoutWriteFoundationIntegrationTests {

	@Container
	static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
			new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
					.withDatabaseName("overload_write_1a_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
	static final String FINGERPRINT = "a".repeat(64);
	static ConfigurableApplicationContext app;
	JdbcTemplate jdbc;
	TransactionTemplate jpa;
	WorkoutWriteGuard guard;
	Account account;
	UUID exerciseId;

	@BeforeAll static void start() {
		app = new SpringApplicationBuilder(OverloadServerApplication.class).web(WebApplicationType.NONE)
				.run("--spring.profiles.active=integration", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
						"--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
						"--GOOGLE_OIDC_ENABLED=false", "--GOOGLE_REAUTH_ENABLED=false", "--OTP_EMAIL_ENABLED=false",
						"--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
		MixedPersistenceAssertions.verify(app);
	}

	@AfterAll static void stop() { if (app != null) app.close(); }

	@BeforeEach void prepare() {
		jdbc = app.getBean(JdbcTemplate.class);
		jpa = new TransactionTemplate(app.getBean("workoutTransactionManager", PlatformTransactionManager.class));
		jpa.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		guard = new WorkoutWriteGuard(jdbc, app.getBean(EntityManagerFactory.class));
		account = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(UUID.randomUUID() + "@example.test"), null);
		exerciseId = jdbc.queryForList("SELECT id FROM exercise_definitions WHERE user_id=? ORDER BY id", UUID.class, account.id()).getFirst();
	}

	@Test void jdbcSeedAndJpaMappingsKeepDefaultsAndExactlyTwoEntities() {
		MixedPersistenceAssertions.verify(app);
		var before = jdbc.queryForList("SELECT * FROM exercise_definitions WHERE user_id=? ORDER BY id", account.id());
		var definitions = app.getBean(ExerciseDefinitionRepository.class).findAllByUserIdAndDataGenerationOrderById(account.id(), 0);
		assertThat(definitions).hasSize(20).allSatisfy(value -> {
			assertThat(value.getVersion()).isZero(); assertThat(value.getDeletedAt()).isNull();
			assertThat(new WorkoutCommandCodec().readStamps(CommandEnvelope.ResourceType.EXERCISE, value.getIntentStamps())).isEmpty();
		});
		assertThat(definitions).extracting(com.overload.overload_server.exercise.ExerciseDefinition::getName)
				.containsExactlyInAnyOrderElementsOf(AccountSeed.WEIGHT_EXERCISE_NAMES);
		var settings = app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(account.id(), 0).orElseThrow();
		assertThat(settings.getVersion()).isZero(); assertThat(settings.getIntentStamps()).isEqualTo("{}");
		assertThat(List.of(settings.getRestLowSeconds(), settings.getRestMediumSeconds(), settings.getRestHighSeconds())).containsExactly(180, 120, 90);
		assertThat(jdbc.queryForList("SELECT * FROM exercise_definitions WHERE user_id=? ORDER BY id", account.id())).isEqualTo(before);
	}

	@Test void receiptPrimaryKeyFingerprintOutcomeAndBodyScopeAreConstrained() {
		var operation = UUID.randomUUID();
		success(account.id(), 0, operation, null);
		fails(() -> success(account.id(), 0, operation, null), "23505");
		fails(() -> jdbc.update("UPDATE sync_operations SET fingerprint='invalid' WHERE operation_id=?", operation), "23514");
		fails(() -> jdbc.update("UPDATE sync_operations SET state='PROCESSING' WHERE operation_id=?", operation), "23514");
		fails(() -> jdbc.update("UPDATE sync_operations SET http_status=204 WHERE operation_id=?", operation), "23514");
		fails(() -> jdbc.update("UPDATE sync_operations SET ack_body='{}'::jsonb WHERE operation_id=?", operation), "23514");
		fails(() -> jdbc.update("UPDATE sync_operations SET ack_body=jsonb_set(ack_body,'{userId}',to_jsonb(?::text)) WHERE operation_id=?", UUID.randomUUID().toString(), operation), "23514");
		fails(() -> jdbc.update("UPDATE sync_operations SET ack_body=jsonb_set(ack_body,'{dataGeneration}','0'::jsonb) WHERE operation_id=?", operation), "23514");
		fails(() -> success(account.id(), 0, UUID.fromString("00000000-0000-1000-8000-000000000000"), null), "23514");
		var other = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(UUID.randomUUID() + "@example.test"), null);
		success(other.id(), 0, operation, null); // operation key is scoped, never global.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_operations WHERE operation_id=?", Integer.class, operation)).isEqualTo(2);
	}

	@Test void onlyTwoTerminal409CodesWithBoundOriginalIntentCanBeStored() {
		for (var code : List.of("VERSION_CONFLICT", "CONFLICT_CONFIRMATION_REQUIRED")) {
			var operation = UUID.randomUUID();
			var intent = rename(operation, account.id(), 0);
			jdbc.update("""
					INSERT INTO sync_operations(user_id,data_generation,operation_id,fingerprint,command_schema_version,command_type,state,http_status,rejection_code,intent_command)
					VALUES (?,?,?, ?,1,'RENAME_EXERCISE','REJECTED',409,?,?::jsonb)
					""", account.id(), 0, operation, FINGERPRINT, code, intent);
			fails(() -> jdbc.update("UPDATE sync_operations SET rejection_code='VALIDATION_FAILED' WHERE operation_id=?", operation), "23514");
			fails(() -> jdbc.update("UPDATE sync_operations SET intent_command='{}'::jsonb WHERE operation_id=?", operation), "23514");
			fails(() -> jdbc.update("UPDATE sync_operations SET change_revision=1 WHERE operation_id=?", operation), "23514");
			fails(() -> jdbc.update("UPDATE sync_operations SET intent_command=jsonb_set(intent_command,'{expectedUserId}',to_jsonb(?::text)) WHERE operation_id=?", UUID.randomUUID().toString(), operation), "23514");
		}
	}

	@Test void ownerGenerationForeignKeysAndDeletedIdReuseRemainProtected() {
		var operation = UUID.randomUUID();
		fails(() -> success(UUID.randomUUID(), 0, operation, null), "23503");
		fails(() -> success(account.id(), 1, operation, null), "23503");
		fails(() -> jdbc.update("INSERT INTO workout_sync_state(user_id,data_generation) VALUES (?,1)", account.id()), "23503");
		fails(() -> jdbc.update("UPDATE exercise_definitions SET data_generation=1 WHERE id=?", exerciseId), "23503");
		fails(() -> jdbc.update("UPDATE exercise_definitions SET version=-1 WHERE id=?", exerciseId), "23514");
		fails(() -> jdbc.update("UPDATE user_workout_settings SET intent_stamps='[]'::jsonb WHERE user_id=?", account.id()), "23514");
		var command = command(UUID.randomUUID());
		jpa.executeWithoutResult(status -> {
			var owner = guard.lockOwner(new SessionPrincipal(account.id()), command);
			assertThatThrownBy(() -> guard.requireUnusedExerciseId(owner, exerciseId)).isInstanceOf(WriteContractViolation.class);
		});
		jdbc.update("UPDATE exercise_definitions SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", exerciseId);
		jpa.executeWithoutResult(status -> {
			var owner = guard.lockOwner(new SessionPrincipal(account.id()), command);
			assertThatThrownBy(() -> guard.requireUnusedExerciseId(owner, exerciseId)).isInstanceOf(WriteContractViolation.class);
		});
		fails(() -> jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,'duplicate')", exerciseId, account.id()), "23505");
	}

	@Test void principalGenerationAndExplicitJpaTransactionAreRequiredForUserLock() {
		var command = command(UUID.randomUUID());
		assertThatThrownBy(() -> guard.lockOwner(new SessionPrincipal(account.id()), command)).isInstanceOf(IllegalStateException.class);
		new TransactionTemplate(app.getBean("transactionManager", PlatformTransactionManager.class)).executeWithoutResult(status ->
				assertThatThrownBy(() -> guard.lockOwner(new SessionPrincipal(account.id()), command)).isInstanceOf(IllegalStateException.class));
		jpa.executeWithoutResult(status -> {
			assertThatThrownBy(() -> guard.lockOwner(new SessionPrincipal(UUID.randomUUID()), command))
					.isInstanceOfSatisfying(WriteContractViolation.class, failure -> assertThat(failure.code()).isEqualTo(WriteContractViolation.Code.OWNER_CONTEXT_MISMATCH));
			var oldGeneration = new WorkoutCommandCodec().prepare(new SessionPrincipal(account.id()), rename(UUID.randomUUID(), account.id(), 1)).envelope();
			assertThatThrownBy(() -> guard.lockOwner(new SessionPrincipal(account.id()), oldGeneration))
					.isInstanceOfSatisfying(WriteContractViolation.class, failure -> assertThat(failure.code()).isEqualTo(WriteContractViolation.Code.DATA_GENERATION_MISMATCH));
		});
	}

	@Test void userLockAndJdbcUseTheSamePhysicalJpaTransactionAndSerializeWriters() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var attempting = new CountDownLatch(1);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> jpa.execute(status -> {
				guard.lockOwner(new SessionPrincipal(account.id()), command(UUID.randomUUID()));
				var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(app.getBean(EntityManagerFactory.class));
				long hibernatePid = holder.getEntityManager().unwrap(Session.class).doReturningWork(connection -> {
					try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) { row.next(); return row.getLong(1); }
				});
				assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Long.class)).isEqualTo(hibernatePid);
				entered.countDown(); await(release);
				return hibernatePid;
			}));
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				var second = workers.submit(() -> jpa.execute(status -> {
					jdbc.execute("SET LOCAL lock_timeout='300ms'"); attempting.countDown();
					return guard.lockOwner(new SessionPrincipal(account.id()), command(UUID.randomUUID()));
				}));
				assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
				fails(() -> {
					try { second.get(10, TimeUnit.SECONDS); }
					catch (Exception failure) { throw new RuntimeException(failure); }
				}, "55P03");
			} finally { release.countDown(); }
			assertThat(first.get(10, TimeUnit.SECONDS)).isPositive();
		}
		jpa.executeWithoutResult(status -> guard.lockOwner(new SessionPrincipal(account.id()), command(UUID.randomUUID())));
	}

	@Test void revisionReceiptAndFeedCommitOrRollBackAsOneSqlUnit() {
		var operation = UUID.randomUUID();
		jpa.executeWithoutResult(status -> {
			guard.lockOwner(new SessionPrincipal(account.id()), command(operation));
			jdbc.update("UPDATE exercise_definitions SET name='fixture committed',version=version+1 WHERE id=? AND user_id=?", exerciseId, account.id());
			jdbc.update("INSERT INTO workout_sync_state(user_id,data_generation,committed_revision) VALUES (?,0,1)", account.id());
			success(account.id(), 0, operation, 1L);
			batch(account.id(), 0, operation, 1L);
		});
		assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_change_batches WHERE user_id=?", Integer.class, account.id())).isEqualTo(1);
		var committedExercise = jdbc.queryForMap("SELECT name,version,intent_stamps FROM exercise_definitions WHERE id=?", exerciseId);
		var rolledBack = UUID.randomUUID();
		assertThatThrownBy(() -> jpa.executeWithoutResult(status -> {
			guard.lockOwner(new SessionPrincipal(account.id()), command(rolledBack));
			jdbc.update("UPDATE exercise_definitions SET name='fixture rollback',version=version+1 WHERE id=? AND user_id=?", exerciseId, account.id());
			jdbc.update("UPDATE workout_sync_state SET committed_revision=2 WHERE user_id=?", account.id());
			success(account.id(), 0, rolledBack, 2L); batch(account.id(), 0, rolledBack, 2L);
			throw new IllegalStateException("isolated fixture rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT committed_revision FROM workout_sync_state WHERE user_id=?", Long.class, account.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_operations WHERE operation_id=?", Integer.class, rolledBack)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_change_batches WHERE revision=2 AND user_id=?", Integer.class, account.id())).isZero();
		assertThat(jdbc.queryForMap("SELECT name,version,intent_stamps FROM exercise_definitions WHERE id=?", exerciseId)).isEqualTo(committedExercise);
	}

	@Test void jpaMetadataOnlyMutationIncrementsRootVersionAndReadsTypedJson() {
		var origin = UUID.randomUUID();
		var stamps = "{\"name\":{\"occurredAt\":\"2026-10-30T00:00:00.000Z\",\"originOperationId\":\"" + origin + "\"}}";
		var originalName = jdbc.queryForObject("SELECT name FROM exercise_definitions WHERE id=?", String.class, exerciseId);
		jpa.executeWithoutResult(status -> {
			guard.lockOwner(new SessionPrincipal(account.id()), command(UUID.randomUUID()));
			var entity = app.getBean(ExerciseDefinitionRepository.class).findByIdAndUserIdAndDataGeneration(exerciseId, account.id(), 0).orElseThrow();
			org.springframework.test.util.ReflectionTestUtils.setField(entity, "intentStamps", stamps);
			((EntityManagerHolder) TransactionSynchronizationManager.getResource(app.getBean(EntityManagerFactory.class))).getEntityManager().flush();
			assertThat(entity.getVersion()).isEqualTo(1);
		});
		var entity = app.getBean(ExerciseDefinitionRepository.class).findByIdAndUserIdAndDataGeneration(exerciseId, account.id(), 0).orElseThrow();
		assertThat(entity.getName()).isEqualTo(originalName); assertThat(entity.getVersion()).isEqualTo(1);
		assertThat(new WorkoutCommandCodec().readStamps(CommandEnvelope.ResourceType.EXERCISE, entity.getIntentStamps()).get("name").originOperationId()).isEqualTo(origin);
	}

	@Test void lockedOwnerTicketCannotEscapeItsJpaTransaction() {
		var owner = jpa.execute(status -> guard.lockOwner(new SessionPrincipal(account.id()), command(UUID.randomUUID())));
		jpa.executeWithoutResult(status -> assertThatThrownBy(() -> guard.requireUnusedExerciseId(owner, UUID.randomUUID())).isInstanceOf(IllegalStateException.class));
		jpa.setReadOnly(true);
		try { jpa.executeWithoutResult(status -> assertThatThrownBy(() -> guard.lockOwner(new SessionPrincipal(account.id()), command(UUID.randomUUID()))).isInstanceOf(IllegalStateException.class)); }
		finally { jpa.setReadOnly(false); }
	}

	@Test void deferredConstraintsRejectPartialOrMismatchedFeedAtCommit() {
		jdbc.update("INSERT INTO workout_sync_state(user_id,data_generation) VALUES (?,0)", account.id());
		var operation = UUID.randomUUID();
		fails(() -> jpa.executeWithoutResult(status -> success(account.id(), 0, operation, 1L)), "23503");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_operations WHERE operation_id=?", Integer.class, operation)).isZero();
		fails(() -> jpa.executeWithoutResult(status -> {
			success(account.id(), 0, operation, 1L); batch(account.id(), 0, operation, 2L);
		}), "23503");
		fails(() -> jdbc.update("INSERT INTO workout_change_batches(user_id,data_generation,revision,operation_id,changes) VALUES (?,0,1,?,'[]'::jsonb)", account.id(), operation), "23514");
		fails(() -> jdbc.update("UPDATE workout_sync_state SET committed_revision=-1 WHERE user_id=?", account.id()), "23514");
	}

	@Test void numeric20Scale6RoundTripsExactlyAndRoundingIsRejectedBeforeSql() {
		jpa.executeWithoutResult(status -> {
			jdbc.execute("CREATE TEMP TABLE weight_contract (weight NUMERIC(20,6) NOT NULL CHECK(weight>=0 AND weight<'Infinity'::numeric)) ON COMMIT DROP");
			for (var raw : List.of("0", "0.000001", "62.5", "99999999999999.999999")) {
				var weight = new WeightKg(raw);
				jdbc.update("INSERT INTO weight_contract(weight) VALUES (?)", weight.decimalValue());
			}
			assertThat(jdbc.queryForList("SELECT weight FROM weight_contract ORDER BY weight", BigDecimal.class))
					.usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("0"), new BigDecimal("0.000001"), new BigDecimal("62.5"), new BigDecimal("99999999999999.999999"));
			assertThatThrownBy(() -> new WeightKg("1.2345678")).isInstanceOf(IllegalArgumentException.class);
			// PostgreSQL itself rounds during the NUMERIC cast: validation must precede binding.
			assertThat(jdbc.queryForObject("SELECT CAST('1.2345678' AS NUMERIC(20,6))", BigDecimal.class)).isEqualByComparingTo("1.234568");
		});
		fails(() -> jpa.executeWithoutResult(status -> jdbc.queryForObject("SELECT CAST('100000000000000' AS NUMERIC(20,6))", BigDecimal.class)), "22003");
	}

	@Test void largeGenerationAndRevisionValuesStayExactWithOwnerFks() {
		var owner = UUID.randomUUID(); long generation = 9_007_199_254_740_993L;
		jdbc.update("INSERT INTO users(id,data_generation) VALUES (?,?)", owner, generation);
		jdbc.update("INSERT INTO workout_sync_state(user_id,data_generation,committed_revision) VALUES (?,?,?)", owner, generation, Long.MAX_VALUE);
		assertThat(Counter.of(jdbc.queryForObject("SELECT committed_revision FROM workout_sync_state WHERE user_id=?", Long.class, owner)).value()).isEqualTo("9223372036854775807");
		var command = new WorkoutCommandCodec().prepare(new SessionPrincipal(owner), rename(UUID.randomUUID(), owner, generation)).envelope();
		jpa.executeWithoutResult(status -> assertThat(guard.lockOwner(new SessionPrincipal(owner), command).dataGeneration().value()).isEqualTo("9007199254740993"));
		fails(() -> jdbc.update("INSERT INTO workout_sync_state(user_id,data_generation) VALUES (?,0)", owner), "23503");
	}

	private CommandEnvelope command(UUID operation) {
		return new WorkoutCommandCodec().prepare(new SessionPrincipal(account.id()), rename(operation, account.id(), 0)).envelope();
	}
	private String rename(UUID operation, UUID owner, long generation) {
		return """
				{"commandSchemaVersion":1,"operationId":"%s","expectedUserId":"%s","dataGeneration":"%s","type":"RENAME_EXERCISE",
				 "expectedVersions":[{"resourceType":"EXERCISE","resourceId":"%s","version":"0"}],
				 "fieldBases":[{"resourceType":"EXERCISE","resourceId":"%s","fieldKey":"name","baseStamp":null}],
				 "sessionControl":null,"occurredAt":"2026-10-30T00:00:00.000Z","payload":{"exerciseId":"%s","name":"fixture"}}
				""".formatted(operation, owner, generation, exerciseId, exerciseId, exerciseId);
	}
	private void success(UUID owner, long generation, UUID operation, Long revision) {
		var ack = """
				{"ackSchemaVersion":1,"userId":"%s","dataGeneration":"%s","operationId":"%s","fingerprint":"%s","resultVersions":[],"result":{}}
				""".formatted(owner, generation, operation, FINGERPRINT);
		jdbc.update("""
				INSERT INTO sync_operations(user_id,data_generation,operation_id,fingerprint,command_schema_version,command_type,state,http_status,ack_body,change_revision)
				VALUES (?,?,?,?,1,'RENAME_EXERCISE','SUCCEEDED',200,?::jsonb,?)
				""", owner, generation, operation, FINGERPRINT, ack, revision);
	}
	private void batch(UUID owner, long generation, UUID operation, long revision) {
		jdbc.update("INSERT INTO workout_change_batches(user_id,data_generation,revision,operation_id,changes) VALUES (?,?,?,?,?::jsonb)",
				owner, generation, revision, operation, "[{\"resourceType\":\"EXERCISE\",\"resourceId\":\"" + exerciseId + "\",\"resourceVersion\":\"1\",\"changeType\":\"UPSERT\"}]");
	}
	private static void await(CountDownLatch latch) {
		try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
		catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
	}
	static void fails(Runnable action, String sqlState) {
		var failure = catchThrowable(action::run);
		assertThat(failure).isNotNull();
		for (var cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql) { assertThat(sql.getSQLState()).isEqualTo(sqlState); return; }
		}
		throw new AssertionError("Expected PostgreSQL SQLSTATE " + sqlState, failure);
	}
}
