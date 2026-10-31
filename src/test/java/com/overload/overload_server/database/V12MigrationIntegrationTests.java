package com.overload.overload_server.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class V12MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_v12_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());

    @Test void upgradePreservesEveryV11RowAndChecksumAndAddsOnlyTheSessionTable() {
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(postgres.getDatabaseName()).isEqualTo("overload_v12_isolated_test");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("11").load();
        old.migrate();
        UUID user = UUID.randomUUID(), routine = UUID.randomUUID(), exercise = UUID.randomUUID(), placement = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)", user);
        jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name,version) VALUES (?,?,0,'preserve',7)", exercise, user);
        jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,0,17,29,43)", user);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,version,created_at,updated_at) VALUES (?,?,0,'preserve',3,now(),now())", routine, user);
        jdbc.update("INSERT INTO routine_weight_placements(id,user_id,data_generation,routine_id,exercise_definition_id,position,set_count,min_reps,max_reps,initial_weight,approved_weight,created_at,updated_at) VALUES (?,?,0,?,?,0,3,6,10,60,65,now(),now())", placement, user, routine, exercise);
        jdbc.update("INSERT INTO progress_change_events(id,user_id,data_generation,routine_id,placement_id,routine_name,exercise_name,previous_weight,next_weight,occurred_at,recorded_at,source) VALUES (?,?,0,?,?,'preserve','preserve',60,65,now(),now(),'ROUTINE_EDITOR')", UUID.randomUUID(), user, routine, placement);
        var tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class);
        var rows = new LinkedHashMap<String, List<String>>();
        for (String table : tables) rows.put(table, jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text", String.class));
        var latest = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("12").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        for (String table : tables) {
            String filter = table.equals("flyway_schema_history") ? " WHERE installed_rank<=11" : "";
            assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t" + filter + " ORDER BY to_jsonb(t)::text", String.class)).isEqualTo(rows.get(table));
        }
        var added = new ArrayList<>(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class));
        added.removeAll(tables); assertThat(added).containsExactly("workout_sessions");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='workout_sessions_one_active_user'", String.class))
                .contains("(user_id)").doesNotContain("data_generation");
        latest.validate(); assertThat(latest.migrate().migrationsExecuted).isZero();
    }
}
