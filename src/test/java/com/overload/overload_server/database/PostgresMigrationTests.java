package com.overload.overload_server.database;

import com.overload.overload_server.OverloadServerApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
class PostgresMigrationTests {

	@Container
	private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_task007_test")
			.withUsername("overload_test")
			.withPassword(UUID.randomUUID().toString());

	@Test
	void migratesEmptyDatabaseOnceAndPreservesUsersAcrossRestart() throws Exception {
		try (var connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var statement = connection.createStatement();
			 var tables = statement.executeQuery(
					 "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
			assertThat(tables.next()).isTrue();
			assertThat(tables.getInt(1)).isZero();
		}

		var userId = UUID.randomUUID();
		List<java.util.Map<String, Object>> history;
		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			var flyway = context.getBean(Flyway.class);
			assertThat(jdbc.queryForList("""
					SELECT table_name FROM information_schema.tables
					WHERE table_schema = 'public' ORDER BY table_name
					""", String.class)).containsExactly("auth_identities", "email_otp_challenges", "email_otp_limits",
					"email_otp_login_claims", "email_otp_rate_limits", "exercise_definitions",
					"flyway_schema_history", "spring_session", "spring_session_attributes", "user_workout_settings", "users");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
			assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
			assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
			assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
			flyway.validate();
			history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
			assertThat(history).hasSize(5);
			assertThat(history.getFirst()).containsEntry("version", "1")
					.containsEntry("script", "V1__create_users.sql").containsEntry("success", true);
			assertThat(history.get(1)).containsEntry("version", "2")
					.containsEntry("script", "V2__create_account_provisioning.sql").containsEntry("success", true);
			assertThat(history.get(2)).containsEntry("version", "3")
					.containsEntry("script", "V3__create_spring_session.sql").containsEntry("success", true);
			assertThat(history.get(3)).containsEntry("version", "4")
					.containsEntry("script", "V4__create_email_otp.sql").containsEntry("success", true);
			assertThat(history.get(4)).containsEntry("version", "5")
					.containsEntry("script", "V5__add_email_reauthentication.sql").containsEntry("success", true);
			jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
			var user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
			assertThat(user).containsEntry("id", userId).containsEntry("data_generation", 0L)
					.containsEntry("nickname", null);
			assertThat(jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?",
					OffsetDateTime.class, userId)).isNotNull();
			assertThat(jdbc.queryForObject("SELECT created_at = updated_at FROM users WHERE id = ?",
					Boolean.class, userId)).isTrue();
		}

		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
					.isEqualTo(history);
			assertThat(jdbc.queryForObject("SELECT id FROM users", UUID.class)).isEqualTo(userId);
			assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void upgradesV1WithoutChangingExistingUsersOrApplyingSeedAtStartup() throws Exception {
		var v1 = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("1").load();
		v1.migrate();
		var existingUser = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var statement = connection.prepareStatement(
					 "INSERT INTO users (id, nickname, data_generation) VALUES (?, 'existing', 7)")) {
			statement.setObject(1, existingUser);
			statement.executeUpdate();
		}
		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForMap("SELECT id, nickname, data_generation FROM users"))
					.containsEntry("id", existingUser).containsEntry("nickname", "existing")
					.containsEntry("data_generation", 7L);
			assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '1'",
					Integer.class)).isEqualTo(1980645960);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identities", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM user_workout_settings", Integer.class)).isZero();
			assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void upgradesV2WithBothExistingMigrationChecksumsAndUserDataPreserved() {
		var v2 = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("2").load();
		v2.migrate();
		var checksums = java.util.Arrays.stream(v2.info().applied()).map(info -> info.getChecksum()).toList();
		try (var context = startApplication(WebApplicationType.NONE)) {
			var latest = context.getBean(Flyway.class);
			assertThat(latest.info().current().getVersion().toString()).isEqualTo("5");
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(2).map(info -> info.getChecksum()).toList())
					.isEqualTo(checksums);
			assertThat(latest.migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void upgradesV3WithAllExistingChecksumsAndUserDataUnchanged() throws Exception {
		var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("3").load();
		old.migrate();
		var checksums = java.util.Arrays.stream(old.info().applied()).map(info -> info.getChecksum()).toList();
		var userId = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var insert = connection.prepareStatement("INSERT INTO users (id, data_generation) VALUES (?, 11)")) {
			insert.setObject(1, userId); insert.executeUpdate();
		}
		try (var context = startApplication(WebApplicationType.NONE)) {
			var latest = context.getBean(Flyway.class);
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(3).map(info -> info.getChecksum()).toList())
					.isEqualTo(checksums);
			assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT data_generation FROM users WHERE id = ?",
					Long.class, userId)).isEqualTo(11L);
			assertThat(latest.migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void upgradesV4PreservingLoginStateCountersSessionBytesAndAllPriorChecksums() {
		var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("4").load();
		old.migrate();
		var checksums = java.util.Arrays.stream(old.info().applied()).map(info -> info.getChecksum()).toList();
		var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		var jdbc = new JdbcTemplate(dataSource);
		var user = UUID.randomUUID(); var identity = UUID.randomUUID(); var challenge = UUID.randomUUID();
		var primary = UUID.randomUUID().toString(); var session = UUID.randomUUID().toString(); var key = "a".repeat(64);
		jdbc.update("INSERT INTO users (id,nickname,data_generation) VALUES (?,'preserved',7)", user);
		jdbc.update("INSERT INTO auth_identities (id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email','migration@example.test')", identity, user);
		jdbc.update("INSERT INTO exercise_definitions (id,user_id,data_generation,name) VALUES (?,?,7,'preserved')", UUID.randomUUID(), user);
		jdbc.update("INSERT INTO user_workout_settings (user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,7,77,66,55)", user);
		jdbc.update("INSERT INTO email_otp_limits (email_key,purpose,last_issued_at,failed_attempts,failure_window_end) VALUES (?,'LOGIN',CURRENT_TIMESTAMP,3,CURRENT_TIMESTAMP+INTERVAL '30 minutes')", key);
		jdbc.update("""
				INSERT INTO email_otp_challenges (id,email_key,purpose,recipient,binding_hash,verifier,state,issued_at,expires_at,accepted_at,failed_attempts)
				VALUES (?,?,'LOGIN','migration@example.test',?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP,2)
				""", challenge, key, "b".repeat(64), new byte[32]);
		jdbc.update("UPDATE email_otp_limits SET active_challenge_id=?", challenge);
		jdbc.update("INSERT INTO email_otp_rate_limits VALUES (?,'LOGIN',CURRENT_TIMESTAMP,4)", "c".repeat(64));
		jdbc.update("INSERT INTO spring_session VALUES (?,?,1,2,60,9999999999999,?)", primary, session, user.toString());
		jdbc.update("INSERT INTO spring_session_attributes VALUES (?,'preserved',?)", primary, new byte[]{1,2,3});
		var tables = List.of("users", "auth_identities", "exercise_definitions", "user_workout_settings", "email_otp_limits", "email_otp_rate_limits", "spring_session");
		var snapshots = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
		var bytes = jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes", byte[].class);
		try (var context = startApplication(WebApplicationType.NONE)) {
			var latest = context.getBean(Flyway.class);
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(4).map(info -> info.getChecksum()).toList()).isEqualTo(checksums);
			assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).isEqualTo(snapshots);
			assertThat(jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes", byte[].class)).isEqualTo(bytes);
			assertThat(jdbc.queryForMap("SELECT purpose,state,failed_attempts,reauth_user_id,reauth_identity_id FROM email_otp_challenges"))
					.containsEntry("purpose", "LOGIN").containsEntry("state", "ACTIVE").containsEntry("failed_attempts", 2)
					.containsEntry("reauth_user_id", null).containsEntry("reauth_identity_id", null);
			assertThat(latest.migrate().migrationsExecuted).isZero();
			assertThatThrownBy(() -> jdbc.update("UPDATE email_otp_challenges SET purpose='REAUTH' WHERE id=?", challenge))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	void enforcesMinimalUserSchemaAndRejectsInvalidWrites() {
		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForList("""
					SELECT column_name || ':' || udt_name || ':' || is_nullable
					FROM information_schema.columns
					WHERE table_schema = 'public' AND table_name = 'users'
					ORDER BY ordinal_position
					""", String.class)).containsExactly(
					"id:uuid:NO", "data_generation:int8:NO", "nickname:text:YES",
					"created_at:timestamptz:NO", "updated_at:timestamptz:NO");
			assertThatThrownBy(() -> jdbc.update(
					"INSERT INTO users (id, data_generation) VALUES (?, -1)", UUID.randomUUID()))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("users_data_generation_nonnegative");
			assertThatThrownBy(() -> jdbc.update("INSERT INTO users (id) VALUES (NULL)"))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.update(
					"INSERT INTO users (id, data_generation) VALUES (?, NULL)", UUID.randomUUID()))
					.isInstanceOf(DataIntegrityViolationException.class);
			for (var column : List.of("created_at", "updated_at")) {
				assertThatThrownBy(() -> jdbc.update(
						"INSERT INTO users (id, " + column + ") VALUES (?, NULL)", UUID.randomUUID()))
						.isInstanceOf(DataIntegrityViolationException.class);
			}
			var userId = UUID.randomUUID();
			jdbc.update("INSERT INTO users (id, nickname) VALUES (?, ?)", userId, "테스트 사용자");
			assertThatThrownBy(() -> jdbc.update("INSERT INTO users (id) VALUES (?)", userId))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, userId))
					.isEqualTo("테스트 사용자");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void preservesHealthHttpContractWithRealPostgres() throws Exception {
		try (var context = startApplication(WebApplicationType.SERVLET);
			 var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			int port = ((WebServerApplicationContext) context).getWebServer().getPort();
			var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/health");
			var get = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(get.statusCode()).isEqualTo(200);
			assertThat(get.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
			assertThat(get.body()).isEqualTo("{\"status\":\"UP\"}");
			var post = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
					.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(post.statusCode()).isEqualTo(405);
		}
	}

	private ConfigurableApplicationContext startApplication(WebApplicationType webApplicationType) {
		return new SpringApplicationBuilder(OverloadServerApplication.class)
				.web(webApplicationType)
				.run("--spring.profiles.active=integration",
						"--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword(),
						"--server.address=127.0.0.1", "--server.port=0");
	}

}
