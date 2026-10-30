package com.overload.testfixture;

import com.overload.overload_server.OverloadServerApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual browser verification against real production beans; never uses a configured developer database. */
public final class WorkoutBrowserEnvironment {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Path DIRECTORY = Path.of("build", "task-008-1c").toAbsolutePath().normalize();

	@TestConfiguration(proxyBeanMethods = false)
	public static class TestEmail {
		@Bean @Primary
		EmailOtpFixtures.FakeSender browserTestSender() { return new EmailOtpFixtures.FakeSender(); }
	}

	public static void main(String[] args) throws Exception {
		Files.createDirectories(DIRECTORY);
		var stop = new AtomicBoolean();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> stop.set(true)));
		try (var postgres = new PostgreSQLContainer("postgres:18.6-alpine")
				.withDatabaseName("overload_browser_isolated").withUsername("overload_test")
				.withPassword(UUID.randomUUID().toString())) {
			postgres.start();
			var key = new byte[32]; new SecureRandom().nextBytes(key);
			try (var app = new SpringApplicationBuilder(OverloadServerApplication.class, TestEmail.class, WorkoutReadFixtures.class)
					.web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
							"--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
							"--spring.datasource.password=" + postgres.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
							"--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key), "--OTP_EMAIL_ENABLED=false",
							"--GOOGLE_OIDC_ENABLED=false", "--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-",
							"--logging.level.root=WARN", "--spring.main.banner-mode=off")) {
				MixedPersistenceAssertions.verify(app);
				var clockType = app.getBean(java.time.Clock.class).getClass().getName();
				if (!clockType.equals("java.time.Clock$SystemClock")) throw new IllegalStateException("Expected production system clock");
				var jdbc = app.getBean(JdbcTemplate.class);
				if (jdbc.queryForObject("SELECT count(*) FROM users", Integer.class) != 0) throw new IllegalStateException("Expected a new empty database");
				app.getBean(EmailOtpFixtures.FakeSender.class).action = message -> {
					try { write("otp.json", Map.of("challengeId", message.issuanceId(), "code", message.code(), "purpose", message.purpose().name())); }
					catch (Exception failure) { throw new IllegalStateException("Cannot record disposable test OTP", failure); }
				};
				var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
				write("ready.json", Map.of("backendOrigin", origin, "containerId", postgres.getContainerId(), "postgresVersion", jdbc.queryForObject("SHOW server_version", String.class),
						"initialUsers", 0, "clockType", clockType, "migrations", jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)));
				System.out.println("ISOLATED_BROWSER_BACKEND_READY " + origin);
				Map<UUID, Map<String, Object>> savedSettings = new HashMap<>();
				while (!stop.get()) {
					var command = DIRECTORY.resolve("command.json");
					if (!Files.exists(command)) { Thread.sleep(150); continue; }
					var request = JSON.readTree(Files.readString(command));
					Files.delete(command);
					String id = request.get("id").asString(), action = request.get("action").asString();
					try {
						UUID userId = request.has("userId") ? UUID.fromString(request.get("userId").asString()) : null;
						switch (action) {
							case "snapshot" -> { }
							case "decorate-owner" -> {
								Objects.requireNonNull(userId);
								jdbc.update("UPDATE exercise_definitions SET name='격리 검증 A 전용 종목' WHERE id=(SELECT id FROM exercise_definitions WHERE user_id=? ORDER BY id LIMIT 1)", userId);
								jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=17,rest_medium_seconds=29,rest_high_seconds=43 WHERE user_id=?", userId);
							}
							case "remove-settings" -> {
								Objects.requireNonNull(userId);
								savedSettings.put(userId, jdbc.queryForMap("SELECT * FROM user_workout_settings WHERE user_id=?", userId));
								jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", userId);
							}
							case "restore-settings" -> {
								var row = Objects.requireNonNull(savedSettings.remove(userId));
								jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
										row.get("user_id"), row.get("data_generation"), row.get("rest_low_seconds"), row.get("rest_medium_seconds"), row.get("rest_high_seconds"), row.get("created_at"), row.get("updated_at"));
							}
							case "disconnect-next-read" -> {
								var killed = new AtomicBoolean();
								WorkoutReadFixtures.observer = event -> {
									if (!event.before() && event.sql().startsWith("SELECT data_generation FROM users") && killed.compareAndSet(false, true)) {
										WorkoutReadFixtures.observer = null;
										try (var statement = event.connection().createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
											result.next();
											var independent = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
											if (!Boolean.TRUE.equals(independent.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, result.getInt(1)))) throw new IllegalStateException("Test connection was not terminated");
										} catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
									}
								};
							}
							case "revoke-session" -> jdbc.update("DELETE FROM spring_session WHERE principal_name=?", Objects.requireNonNull(userId).toString());
							case "stop" -> stop.set(true);
							default -> throw new IllegalArgumentException("Unsupported test command");
						}
						write("result.json", Map.of("id", id, "action", action, "ok", true, "snapshot", snapshot(jdbc)));
					} catch (Exception failure) {
						write("result.json", Map.of("id", id, "action", action, "ok", false, "failureType", failure.getClass().getSimpleName()));
					}
				}
				WorkoutReadFixtures.observer = null;
			} finally {
				Files.deleteIfExists(DIRECTORY.resolve("otp.json"));
				Files.deleteIfExists(DIRECTORY.resolve("ready.json"));
			}
		}
		System.out.println("ISOLATED_BROWSER_BACKEND_STOPPED");
	}

	private static Map<String, Object> snapshot(JdbcTemplate jdbc) {
		var sessions = jdbc.query("SELECT session_id,principal_name,creation_time,expiry_time,max_inactive_interval FROM spring_session ORDER BY creation_time", (row, index) -> {
			try {
				var value = new LinkedHashMap<String, Object>();
				value.put("cookieSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(row.getString("session_id").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
				value.put("userId", row.getString("principal_name")); value.put("createdAtMs", row.getLong("creation_time"));
				value.put("expiresAtMs", row.getLong("expiry_time")); value.put("maxInactiveSeconds", row.getInt("max_inactive_interval"));
				return value;
			} catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
		});
		return Map.of("users", jdbc.queryForList("SELECT id::text AS id,data_generation::text AS generation FROM users ORDER BY id"),
				"exercises", jdbc.queryForList("SELECT id::text,user_id::text,data_generation::text,name,created_at::text,updated_at::text FROM exercise_definitions ORDER BY user_id,id"),
				"settings", jdbc.queryForList("SELECT user_id::text,data_generation::text,rest_low_seconds,rest_medium_seconds,rest_high_seconds,created_at::text,updated_at::text FROM user_workout_settings ORDER BY user_id"),
				"identities", jdbc.queryForList("SELECT id::text,user_id::text,provider FROM auth_identities ORDER BY user_id,id"), "sessions", sessions);
	}

	private static synchronized void write(String file, Object data) throws Exception {
		var temporary = DIRECTORY.resolve(file + ".tmp");
		Files.writeString(temporary, JSON.writeValueAsString(data));
		Files.move(temporary, DIRECTORY.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
