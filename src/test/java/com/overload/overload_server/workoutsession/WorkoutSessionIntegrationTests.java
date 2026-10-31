package com.overload.overload_server.workoutsession;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.*;
import com.overload.testfixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class WorkoutSessionIntegrationTests {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BASE = "/api/v1/workout-sessions";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_session_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;
    static JdbcTemplate jdbc;
    Account owner; Browser browser; String csrf, email;
    UUID routine, exercise, placement, cardio, session, device;

    @BeforeAll static void startApp() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(POSTGRES.getDatabaseName()).isEqualTo("overload_session_isolated_test");
        var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, WorkoutReadFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
                        "--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key), "--OTP_EMAIL_ENABLED=false", "--GOOGLE_OIDC_ENABLED=false",
                        "--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
        jdbc = app.getBean(JdbcTemplate.class); MixedPersistenceAssertions.verify(app);
    }
    @BeforeEach void prepare() throws Exception {
        WorkoutReadFixtures.observer = null; EmailOtpFixtures.CLOCK.reset(); app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        jdbc.update("DELETE FROM email_otp_rate_limits");
        email = UUID.randomUUID() + "@example.test";
        owner = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(email), null);
        exercise = jdbc.queryForObject("SELECT id FROM exercise_definitions WHERE user_id=? ORDER BY id LIMIT 1", UUID.class, owner.id());
        routine = UUID.randomUUID(); placement = UUID.randomUUID(); cardio = UUID.randomUUID(); session = UUID.randomUUID(); device = UUID.randomUUID();
        browser = new Browser(); browser.login(email); csrf = browser.csrf();
        assertThat(browser.send("POST", "/api/v1/routines", json(routineBody()), csrf).statusCode()).isEqualTo(201);
    }
    @AfterEach void cleanup() { WorkoutReadFixtures.observer = null; if (browser != null) browser.close(); }
    @AfterAll static void stop() { if (app != null) app.close(); }

    @Test void startCopiesImmutablePlanDistinctIdsAndOriginalClientDate() throws Exception {
        assertThat(node(browser.get(BASE + "/active")).get("session").isNull()).isTrue();
        var response = start(startBody()); assertThat(response.statusCode()).isEqualTo(201); headers(response);
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo(path());
        var result = node(response); var plan = result.get("initialPlan");
        assertThat(result.get("startedAt").asString()).isEqualTo("2026-10-31T16:00:00.123Z");
        assertThat(result.get("workoutDate").asString()).isEqualTo("2026-11-01");
        assertThat(result.get("writerEpoch").asString()).isEqualTo("0");
        assertThat(result.get("localRevision").asString()).isEqualTo("0");
        var weight = plan.get("weightExercises").get(0);
        assertThat(weight.get("sessionExerciseId").asString()).isNotEqualTo(placement.toString()).isNotEqualTo(exercise.toString());
        assertThat(weight.get("sourcePlacementId").asString()).isEqualTo(placement.toString());
        assertThat(weight.get("approvedWeightKg").asString()).isEqualTo("60");
        assertThat(plan.get("cardioActivities").get(0).get("sessionActivityId").asString()).isNotEqualTo(cardio.toString());
        assertThat(plan.get("cardioActivities").get(0).get("plan").get("targetDistanceKm").isNull()).isTrue();
        var before = row();
        assertThat(node(browser.get(BASE + "/active")).get("session")).isEqualTo(result);
        assertThat(node(browser.get(path()))).isEqualTo(result);
        assertThat(start(startBody()).statusCode()).isEqualTo(200);
        assertThat(row()).isEqualTo(before);
    }

    @Test void routineRenameApprovalAndDeletionNeverRewriteStartedPlanOrPreventStartRetry() throws Exception {
        var original = node(start(startBody())).get("initialPlan");
        var update = updateRoutine(); update.put("name", "changed");
        update.put("weightApprovals", List.of(Map.of("placementId", placement, "nextApprovedWeightKg", "65", "occurredAt", "2026-10-31T10:00:00Z")));
        assertThat(browser.send("PUT", routinePath(), json(update), csrf).statusCode()).isEqualTo(200);
        assertThat(browser.send("PATCH", "/api/v1/exercises/" + exercise, json(Map.of("dataGeneration", "0", "name", "renamed")), csrf).statusCode()).isEqualTo(200);
        assertThat(browser.send("DELETE", routinePath(), json(Map.of("dataGeneration", "0", "expectedVersion", "1")), csrf).statusCode()).isEqualTo(204);
        assertThat(node(browser.get(path())).get("initialPlan")).isEqualTo(original);
        assertThat(node(start(startBody())).get("initialPlan")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM progress_change_events WHERE routine_id=?", Integer.class, routine)).isEqualTo(1);
    }

    @Test void takeoverIsExplicitEpochCheckedRetryableAndNeverExtendsSyncTime() throws Exception {
        node(start(startBody())); var before = row(); UUID next = UUID.randomUUID();
        try (var other = new Browser()) {
            EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(61));
            other.login(email); String token = other.csrf();
            assertThat(node(other.get(path())).get("editorDeviceId").asString()).isEqualTo(device.toString());
            assertThat(row()).isEqualTo(before);
            var request = takeoverBody(next, "0");
            var taken = node(other.send("POST", path() + "/takeover", json(request), token));
            assertThat(taken.get("writerEpoch").asString()).isEqualTo("1");
            assertThat(taken.get("editorDeviceId").asString()).isEqualTo(next.toString());
            assertThat(taken.get("lastWorkoutSyncedAt").asString()).isEqualTo(node(browser.get(path())).get("lastWorkoutSyncedAt").asString());
            assertThat(row().get("last_workout_synced_at")).isEqualTo(before.get("last_workout_synced_at"));
            var accepted = row();
            assertThat(node(start(startBody())).get("editorDeviceId").asString()).isEqualTo(next.toString());
            assertThat(row()).isEqualTo(accepted);
            assertThat(other.send("POST", path() + "/takeover", json(request), token).statusCode()).isEqualTo(200);
            assertThat(row()).isEqualTo(accepted);
            error(browser.send("POST", path() + "/takeover", json(takeoverBody(device, "0")), csrf), 409, "EDITOR_OWNERSHIP_LOST");
            assertThat(row()).isEqualTo(accepted);
            assertThat(node(browser.send("POST", path() + "/takeover", json(takeoverBody(device, "1")), csrf)).get("writerEpoch").asString()).isEqualTo("2");
            error(other.send("POST", path() + "/takeover", json(request), token), 409, "EDITOR_OWNERSHIP_LOST");
        }
    }

    @Test void ownershipGenerationCsrfAndProtocolBoundaries() throws Exception {
        node(start(startBody())); var before = row();
        try (var anonymous = new Browser()) { error(anonymous.get(path()), 401, "AUTHENTICATION_REQUIRED"); }
        error(browser.send("POST", path() + "/takeover", json(takeoverBody(device, "0")), null), 403, "CSRF_INVALID");
        var stale = takeoverBody(device, "0"); stale.put("dataGeneration", "1");
        error(browser.send("POST", path() + "/takeover", json(stale), csrf), 409, "DATA_GENERATION_MISMATCH");
        try (var foreign = new Browser()) {
            foreign.login(UUID.randomUUID() + "@example.test"); var token = foreign.csrf();
            error(foreign.get(path()), 404, "SESSION_NOT_FOUND");
            error(foreign.send("POST", path() + "/takeover", json(takeoverBody(device, "0")), token), 404, "SESSION_NOT_FOUND");
            error(foreign.send("POST", BASE, json(startBody()), token), 409, "SESSION_ID_CONFLICT");
            var foreignStart = startBody(); foreignStart.put("id", UUID.randomUUID());
            error(foreign.send("POST", BASE, json(foreignStart), token), 404, "ROUTINE_NOT_FOUND");
        }
        error(browser.get(path() + "?editorDeviceId=" + device), 400, "INVALID_REQUEST");
        error(browser.send("GET", path(), "{}", null), 400, "INVALID_REQUEST");
        error(browser.get(BASE + "/bad-id"), 400, "INVALID_REQUEST");
        assertThat(browser.send("DELETE", path(), "{}", csrf).statusCode()).isEqualTo(405);
        assertThat(row()).isEqualTo(before);
    }

    @Test void fixedIdCannotChangeStartFactsAndSecondActiveIsRejected() throws Exception {
        node(start(startBody())); var before = row();
        var changed = startBody(); changed.put("editorDeviceId", UUID.randomUUID());
        error(start(changed), 409, "SESSION_ID_CONFLICT");
        changed = startBody(); changed.put("id", UUID.randomUUID());
        error(start(changed), 409, "ACTIVE_SESSION_EXISTS");
        assertThat(row()).isEqualTo(before);
    }

    @Test void databaseProtectsInitialPlanOwnerGenerationAndOneActiveAccount() throws Exception {
        node(start(startBody()));
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET initial_plan_snapshot=initial_plan_snapshot || '{\"routineName\":\"tampered\"}'::jsonb WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET started_at=started_at+interval '1 second' WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET data_generation=1 WHERE id=?", owner.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO workout_sessions SELECT ?,user_id,data_generation,source_routine_id,status,version,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,writer_epoch,local_revision,initial_plan_snapshot,last_workout_synced_at,created_at,updated_at FROM workout_sessions WHERE id=?", UUID.randomUUID(), session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET writer_epoch=-1 WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET local_revision=-1 WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void concurrentStartsReachTheDatabaseUniqueConstraint() throws Exception {
        var barrier = new CyclicBarrier(2);
        WorkoutReadFixtures.observer = event -> {
            if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith("insert into workout_sessions")) await(barrier);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var second = startBody(); second.put("id", UUID.randomUUID());
            var one = executor.submit(() -> start(startBody())); var two = executor.submit(() -> start(second));
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).statusCode(), two.get(15, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
        } finally { WorkoutReadFixtures.observer = null; }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE user_id=?", Integer.class, owner.id())).isEqualTo(1);
    }

    @Test void competingTakeoversChangeEpochOnce() throws Exception {
        node(start(startBody())); var barrier = new CyclicBarrier(2);
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (event.before() && sql.contains("from workout_sessions ") && sql.contains("for ")) await(barrier);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf));
            var two = executor.submit(() -> browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf));
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).statusCode(), two.get(15, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { WorkoutReadFixtures.observer = null; }
        assertThat(row().get("writer_epoch")).isEqualTo(1L);
    }

    @ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
    void startLocksCurrentRoutineUntilInitialPlanIsCommitted(String method) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var observed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (!event.before() && sql.contains("from routines ") && sql.contains("for share") && observed.compareAndSet(false, true)) {
                locked.countDown(); await(release);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var starting = executor.submit(() -> start(startBody()));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var editing = executor.submit(() -> editRoutine(method));
            try { assertLockWait("routines"); } finally { release.countDown(); }
            var result = node(starting.get(15, TimeUnit.SECONDS));
            assertThat(editing.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(method.equals("PUT") ? 200 : 204);
            assertThat(result.get("initialPlan").get("routineVersion").asString()).isEqualTo("0");
            assertThat(result.get("initialPlan").get("weightExercises").get(0).get("setCount").asInt()).isEqualTo(3);
        } finally { release.countDown(); WorkoutReadFixtures.observer = null; }
    }

    @ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
    void concurrentSourceChangeCannotProduceAMixedInitialPlan(String method) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var observed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (!event.before() && sql.contains("from routines ") && sql.contains("for no key update") && observed.compareAndSet(false, true)) {
                locked.countDown(); await(release);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var editing = executor.submit(() -> editRoutine(method));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var starting = executor.submit(() -> start(startBody()));
            try { assertLockWait("routines"); } finally { release.countDown(); }
            assertThat(editing.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(method.equals("PUT") ? 200 : 204);
            assertThat(starting.get(15, TimeUnit.SECONDS).statusCode()).isIn(404, 409);
        } finally { release.countDown(); WorkoutReadFixtures.observer = null; }
        if (method.equals("PUT")) {
            var plan = node(start(startBody())).get("initialPlan");
            assertThat(plan.get("routineVersion").asString()).isEqualTo("1");
            assertThat(plan.get("weightExercises").get(0).get("setCount").asInt()).isEqualTo(4);
        } else error(start(startBody()), 404, "ROUTINE_NOT_FOUND");
    }

    @Test void insertFailureRollsBackWithoutChangingSourceOrProvisioningAnything() throws Exception {
        var source = jdbc.queryForMap("SELECT * FROM routines WHERE id=?", routine);
        WorkoutReadFixtures.observer = event -> {
            if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith("insert into workout_sessions"))
                throw new org.springframework.dao.DataAccessResourceFailureException("isolated failure");
        };
        try { error(start(startBody()), 503, "WORKOUT_DATA_UNAVAILABLE"); } finally { WorkoutReadFixtures.observer = null; }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE user_id=?", Integer.class, owner.id())).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM routines WHERE id=?", routine)).isEqualTo(source);
        assertThat(start(startBody()).statusCode()).isEqualTo(201);
    }

    @Test void counterOverflowRollsBackTakeoverAndLargeGenerationIsExact() throws Exception {
        node(start(startBody())); jdbc.update("UPDATE workout_sessions SET writer_epoch=?,version=? WHERE id=?", Long.MAX_VALUE, Long.MAX_VALUE, session);
        var before = row();
        error(browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), Long.toString(Long.MAX_VALUE))), csrf), 409, "WRITE_CONFLICT");
        assertThat(row()).isEqualTo(before);
        // Isolated reset fixture only, not a product reset endpoint or developer database mutation.
        jdbc.update("DELETE FROM workout_sessions WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM routine_weight_placements WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM routine_cardio_placements WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM routines WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", owner.id());
        jdbc.update("UPDATE users SET data_generation=9007199254740993 WHERE id=?", owner.id());
        error(start(startBody()), 409, "DATA_GENERATION_MISMATCH");
        assertThat(node(browser.get(BASE + "/active")).get("dataGeneration").asString()).isEqualTo("9007199254740993");
        error(browser.get(path()), 404, "SESSION_NOT_FOUND");
        String generation = "9007199254740993";
        exercise = UUID.randomUUID(); routine = UUID.randomUUID(); placement = UUID.randomUUID(); cardio = UUID.randomUUID(); session = UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,?,'new generation')", exercise, owner.id(), Long.parseLong(generation));
        var source = routineBody(); source.put("dataGeneration", generation);
        assertThat(browser.send("POST", "/api/v1/routines", json(source), csrf).statusCode()).isEqualTo(201);
        var input = startBody(); input.put("dataGeneration", generation);
        assertThat(node(start(input)).get("dataGeneration").asString()).isEqualTo(generation);
        var second = new LinkedHashMap<>(input); second.put("id", UUID.randomUUID());
        error(start(second), 409, "ACTIVE_SESSION_EXISTS");
    }

    @Test void startSharesOneRepeatableReadJpaJdbcTransactionWithoutMaterializingPlannedSets() throws Exception {
        var update = updateRoutine();
        update.put("weightPlacements", List.of(Map.of("mode", "EXISTING", "id", placement, "setCount", Integer.MAX_VALUE,
                "minReps", Integer.MAX_VALUE, "maxReps", Integer.MAX_VALUE)));
        assertThat(browser.send("PUT", routinePath(), json(update), csrf).statusCode()).isEqualTo(200);
        var backends = new HashSet<Integer>(); var transactions = new HashSet<Long>();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (event.before() && (sql.contains("from users") || sql.contains("from routines ") || sql.startsWith("insert into workout_sessions"))) {
                try (var statement = event.connection().createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid(),txid_current(),current_setting('transaction_isolation')")) {
                    result.next(); backends.add(result.getInt(1)); transactions.add(result.getLong(2));
                    assertThat(result.getString(3)).isEqualTo("repeatable read");
                    assertThat(event.connection().isReadOnly()).isFalse();
                } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            }
        };
        JsonNode plan;
        try { plan = node(start(startBody())).get("initialPlan"); } finally { WorkoutReadFixtures.observer = null; }
        assertThat(backends).hasSize(1); assertThat(transactions).hasSize(1);
        assertThat(plan.get("weightExercises").size()).isEqualTo(1);
        assertThat(plan.get("weightExercises").get(0).get("setCount").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(row().get("initial_plan_snapshot").toString().length()).isLessThan(2000);
    }

    @Test void failedTakeoverFlushRollsBackDeviceEpochAndCountersTogether() throws Exception {
        node(start(startBody())); var before = row();
        WorkoutReadFixtures.observer = event -> {
            if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith("update workout_sessions"))
                throw new org.springframework.dao.DataAccessResourceFailureException("isolated takeover failure");
        };
        try { error(browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf), 503, "WORKOUT_DATA_UNAVAILABLE"); }
        finally { WorkoutReadFixtures.observer = null; }
        assertThat(row()).isEqualTo(before);
    }

    @Test void generationResetWaitsForStartOwnerLockAndOldRequestCannotCrossTheBoundary() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var observed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (!event.before() && sql.contains("from routines ") && sql.contains("for share") && observed.compareAndSet(false, true)) {
                locked.countDown(); await(release);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var starting = executor.submit(() -> start(startBody()));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var reset = executor.submit(() -> {
                // Separate test-only reset transaction, entirely inside this disposable database.
                var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var isolated = new JdbcTemplate(source);
                new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(source))
                        .executeWithoutResult(status -> {
                            isolated.queryForObject("SELECT data_generation FROM users WHERE id=? FOR UPDATE", Long.class, owner.id());
                            for (String table : List.of("workout_sessions", "routine_weight_placements", "routine_cardio_placements", "routines", "exercise_definitions", "user_workout_settings"))
                                isolated.update("DELETE FROM " + table + " WHERE user_id=?", owner.id());
                            isolated.update("UPDATE users SET data_generation=1 WHERE id=?", owner.id());
                        });
            });
            try { assertLockWait("users"); } finally { release.countDown(); }
            assertThat(starting.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            reset.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); WorkoutReadFixtures.observer = null; }
        error(start(startBody()), 409, "DATA_GENERATION_MISMATCH");
        assertThat(node(browser.get(BASE + "/active")).get("session").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE user_id=?", Integer.class, owner.id())).isZero();
    }

    HttpResponse<String> editRoutine(String method) throws Exception {
        return browser.send(method, routinePath(), json(method.equals("PUT") ? updateRoutine() : Map.of("dataGeneration", "0", "expectedVersion", "0")), csrf);
    }
    Map<String, Object> routineBody() {
        return new LinkedHashMap<>(Map.of("id", routine, "dataGeneration", "0", "name", "session source", "weightPlacements",
                List.of(Map.of("id", placement, "exerciseDefinitionId", exercise, "setCount", 3, "minReps", 6, "maxReps", 10, "initialWeightKg", "60")),
                "cardioPlacements", List.of(Map.of("id", cardio, "plan", cardioPlan()))));
    }
    Map<String, Object> updateRoutine() {
        return new LinkedHashMap<>(Map.of("dataGeneration", "0", "expectedVersion", "0", "name", "updated source", "weightPlacements",
                List.of(Map.of("mode", "EXISTING", "id", placement, "setCount", 4, "minReps", 6, "maxReps", 10)),
                "cardioPlacements", List.of(Map.of("mode", "EXISTING", "id", cardio, "plan", cardioPlan())), "weightApprovals", List.of()));
    }
    static Map<String, Object> cardioPlan() {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", "TREADMILL"); result.put("name", "run"); result.put("targetSeconds", 600);
        for (String key : List.of("targetDistanceKm", "intensity", "inclinePercent", "resistance")) result.put(key, null);
        return result;
    }
    Map<String, Object> startBody() {
        return new LinkedHashMap<>(Map.of("id", session, "dataGeneration", "0", "routineId", routine, "editorDeviceId", device,
                "startedAt", "2026-10-31T16:00:00.123Z", "workoutDate", "2026-11-01", "startedTimeZone", "Asia/Seoul"));
    }
    static Map<String, Object> takeoverBody(UUID device, String epoch) {
        return new LinkedHashMap<>(Map.of("dataGeneration", "0", "editorDeviceId", device, "expectedWriterEpoch", epoch));
    }
    String path() { return BASE + "/" + session; }
    String routinePath() { return "/api/v1/routines/" + routine; }
    HttpResponse<String> start(Map<String, Object> body) throws Exception { return browser.send("POST", BASE, json(body), csrf); }
    Map<String, Object> row() { return jdbc.queryForMap("SELECT * FROM workout_sessions WHERE id=?", session); }
    static String json(Object value) { return JSON.writeValueAsString(value); }
    static JsonNode node(HttpResponse<String> response) { headers(response); assertThat(response.statusCode()).as(response.body()).isIn(200, 201); return JSON.readTree(response.body()); }
    static void error(HttpResponse<String> response, int status, String code) {
        headers(response); assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(json(Map.of("code", code))));
    }
    static void headers(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-ID").orElse("")).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
    static void await(CyclicBarrier barrier) { try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); } }
    static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    static void assertLockWait(String table) throws Exception {
        var independent = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting;
        do {
            waiting = independent.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?)", Boolean.class, "%from " + table + " %");
            if (!waiting) Thread.sleep(20);
        } while (!waiting && System.nanoTime() < deadline);
        assertThat(waiting).isTrue();
    }
    static final class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        void login(String email) throws Exception {
            String token = csrf(); var issued = send("POST", "/api/v1/auth/email-otp/request", json(Map.of("email", email)), token);
            assertThat(issued.statusCode()).as(issued.body()).isEqualTo(202);
            var message = app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
            assertThat(send("POST", "/api/v1/auth/email-otp/verify", json(Map.of("email", email, "challengeId", message.issuanceId(), "code", message.code())), token).statusCode()).isEqualTo(204);
        }
        String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort() + path)).timeout(Duration.ofSeconds(20));
            if (token != null) request.header("X-CSRF-TOKEN", token);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { client.close(); }
    }
}
