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
class V13MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_v13_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());

    @Test void upgradePreservesAllV12ColumnsAndBackfillsOnlyMutablePlanAndIdentityRegistry() {
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(postgres.getDatabaseName()).isEqualTo("overload_v13_isolated_test");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("12").load();
        old.migrate();
        UUID user=UUID.randomUUID(), routine=UUID.randomUUID(), definition=UUID.randomUUID(), placement=UUID.randomUUID(), session=UUID.randomUUID(), device=UUID.randomUUID(), item=UUID.randomUUID(), activity=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)",user);
        jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,'preserve')",definition,user);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,version,created_at,updated_at) VALUES (?,?,0,'preserve',2,now(),now())",routine,user);
        jdbc.update("INSERT INTO routine_weight_placements(id,user_id,data_generation,routine_id,exercise_definition_id,position,set_count,min_reps,max_reps,initial_weight,approved_weight,created_at,updated_at) VALUES (?,?,0,?,?,0,3,6,10,60,65,now(),now())",placement,user,routine,definition);
        jdbc.update("INSERT INTO progress_change_events(id,user_id,data_generation,routine_id,placement_id,routine_name,exercise_name,previous_weight,next_weight,occurred_at,recorded_at,source) VALUES (?,?,0,?,?,'preserve','preserve',60,65,now(),now(),'ROUTINE_EDITOR')",UUID.randomUUID(),user,routine,placement);
        String plan="""
                {"schemaVersion":1,"routineId":"%s","routineName":"preserve","routineVersion":"2",
                 "weightExercises":[{"sessionExerciseId":"%s","sourcePlacementId":"%s","exerciseDefinitionId":"%s","exerciseName":"preserve","setCount":3,"minReps":6,"maxReps":10,"approvedWeightKg":"65"}],
                 "cardioActivities":[{"sessionActivityId":"%s","sourcePlacementId":"%s","plan":{"kind":"TREADMILL","name":"run","targetSeconds":600,"targetDistanceKm":null,"intensity":null,"inclinePercent":null,"resistance":null}}]}
                """.formatted(routine,item,placement,definition,activity,UUID.randomUUID());
        jdbc.update("INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,status,version,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,writer_epoch,local_revision,initial_plan_snapshot,last_workout_synced_at,created_at,updated_at) VALUES (?,?,0,?,'ACTIVE',4,'2026-10-31T16:00:00Z','2026-11-01','Asia/Seoul',?,?,2,0,?::jsonb,now(),now(),now())",session,user,routine,device,device,plan);
        var tables=jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class);
        var columns=new LinkedHashMap<String,String>(); var rows=new LinkedHashMap<String,List<String>>();
        for(String table:tables){
            var names=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position",String.class,table);
            String selected=String.join(",",names.stream().map(n->"\""+n+"\"").toList()); columns.put(table,selected);
            rows.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM (SELECT "+selected+" FROM "+table+") t ORDER BY to_jsonb(t)::text",String.class));
        }
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        for(String table:tables){
            String filter=table.equals("flyway_schema_history")?" WHERE installed_rank<=12":"";
            assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM (SELECT "+columns.get(table)+" FROM "+table+filter+") t ORDER BY to_jsonb(t)::text",String.class)).as(table).isEqualTo(rows.get(table));
        }
        assertThat(jdbc.queryForObject("SELECT applied_plan_snapshot->'weightExercises'->0->>'sessionExerciseId' FROM workout_sessions WHERE id=?",String.class,session)).isEqualTo(item.toString());
        assertThat(jdbc.queryForObject("SELECT applied_plan_snapshot->'cardioActivities'->0->'plan'->>'kind' FROM workout_sessions WHERE id=?",String.class,session)).isEqualTo("TREADMILL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_exercises WHERE session_id=?",Integer.class,session)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM performed_sets",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_weight_approval_requests",Integer.class)).isZero();
        latest.validate(); assertThat(latest.migrate().migrationsExecuted).isZero();
    }
}
