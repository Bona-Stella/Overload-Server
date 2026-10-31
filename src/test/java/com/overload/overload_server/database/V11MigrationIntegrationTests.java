package com.overload.overload_server.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.SQLException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class V11MigrationIntegrationTests {
	@Container final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_v11_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
	JdbcTemplate jdbc() {
		assertThat(postgres.getMappedPort(5432)).isNotIn(5432, 5433);
		assertThat(postgres.getDatabaseName()).isEqualTo("overload_v11_isolated_test");
		return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
	}
	Flyway flyway(String target) { return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).locations("classpath:db/migration").target(target).load(); }
	@Test void v10UpgradeAddsOnlyFourTablesAndPreservesAllPreviousRowsAndChecksums() {
		flyway("10").migrate(); var jdbc = jdbc(); UUID user = UUID.randomUUID(), exercise = UUID.randomUUID();
		jdbc.update("INSERT INTO users(id,nickname) VALUES (?,'preserve')", user);
		jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name,version,deleted_at) VALUES (?,?,0,'preserve',7,current_timestamp)", exercise, user);
		jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds,version) VALUES (?,0,17,29,43,9)", user);
		var tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class);
		var rows = new LinkedHashMap<String, List<String>>();
		for (String table : tables) rows.put(table, jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text", String.class));
		assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(1);
		for (String table : tables) {
			String where = table.equals("flyway_schema_history") ? " WHERE installed_rank<=10" : "";
			assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t" + where + " ORDER BY to_jsonb(t)::text", String.class)).isEqualTo(rows.get(table));
		}
		var added = new ArrayList<>(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class)); added.removeAll(tables);
		assertThat(added).containsExactlyInAnyOrder("routines", "routine_weight_placements", "routine_cardio_placements", "progress_change_events");
		assertThat(jdbc.queryForList("SELECT numeric_precision,numeric_scale FROM information_schema.columns WHERE table_name IN ('routine_weight_placements','routine_cardio_placements','progress_change_events') AND data_type='numeric'"))
				.allSatisfy(row -> assertThat(row).containsEntry("numeric_precision", 20).containsEntry("numeric_scale", 6));
		flyway("11").validate(); assertThat(flyway("11").migrate().migrationsExecuted).isZero();
	}
	@Test void ownerGenerationRangeOrderingAndEventForeignKeysAreEnforcedByPostgres() {
		flyway("11").migrate(); var jdbc = jdbc(); UUID user = UUID.randomUUID(), foreign = UUID.randomUUID(), routine = UUID.randomUUID(), exercise = UUID.randomUUID(), first = UUID.randomUUID();
		jdbc.update("INSERT INTO users(id) VALUES (?),(?)", user, foreign);
		jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,'same')", exercise, user);
		jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'same',now(),now())", routine, user);
		String insert = "INSERT INTO routine_weight_placements(id,user_id,data_generation,routine_id,exercise_definition_id,position,set_count,min_reps,max_reps,initial_weight,approved_weight,created_at,updated_at) VALUES (?,?,0,?,?,?,3,6,10,0,0,now(),now())";
		jdbc.update(insert, first, user, routine, exercise, 0);
		state("23505", () -> jdbc.update(insert, UUID.randomUUID(), user, routine, exercise, 0));
		jdbc.update(insert, UUID.randomUUID(), user, routine, exercise, 1); // repeated exercise is intentionally valid
		state("23503", () -> jdbc.update(insert, UUID.randomUUID(), foreign, routine, exercise, 2));
		state("23514", () -> jdbc.update("UPDATE routine_weight_placements SET set_count=0 WHERE id=?", first));
		state("23514", () -> jdbc.update("UPDATE routine_weight_placements SET approved_weight='NaN' WHERE id=?", first));
		state("23503", () -> jdbc.update("UPDATE routine_weight_placements SET data_generation=1 WHERE id=?", first));
		jdbc.update("UPDATE routine_weight_placements SET deleted_at=now() WHERE id=?", first);
		jdbc.update(insert, UUID.randomUUID(), user, routine, exercise, 0);
		state("23505", () -> jdbc.update("UPDATE routine_weight_placements SET deleted_at=null WHERE id=?", first));
		String fact = "INSERT INTO progress_change_events(id,user_id,data_generation,routine_id,placement_id,routine_name,exercise_name,previous_weight,next_weight,occurred_at,recorded_at,source) VALUES (?,?,0,?,?,'same','same',0,1,now(),now(),'ROUTINE_EDITOR')";
		state("23503", () -> jdbc.update(fact, UUID.randomUUID(), foreign, routine, first));
		jdbc.update(fact, UUID.randomUUID(), user, routine, first);
		state("23503", () -> jdbc.update("DELETE FROM routine_weight_placements WHERE id=?", first));
	}
	static void state(String expected, Runnable action) {
		assertThatThrownBy(action::run).satisfies(failure -> {
			Throwable cause = failure; while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
			assertThat(cause).isInstanceOf(SQLException.class); assertThat(((SQLException) cause).getSQLState()).isEqualTo(expected);
		});
	}
}
