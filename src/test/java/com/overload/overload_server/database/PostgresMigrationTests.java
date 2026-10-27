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
					""", String.class)).containsExactly("flyway_schema_history", "users");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
			assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
			assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
			assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
			flyway.validate();
			history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
			assertThat(history).hasSize(1);
			assertThat(history.getFirst()).containsEntry("version", "1")
					.containsEntry("script", "V1__create_users.sql").containsEntry("success", true);
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
