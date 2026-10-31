package com.overload.overload_server.workoutsession;

import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class WorkoutSessionRepository {
    @PersistenceContext private EntityManager entityManager;
    private final JdbcTemplate jdbc;
    WorkoutSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    long generation(UUID owner, boolean lock) {
        var values = jdbc.query("SELECT data_generation FROM users WHERE id=?" + (lock ? " FOR SHARE" : ""),
                (row, index) -> row.getLong(1), owner);
        if (values.isEmpty()) throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.AUTHENTICATION_REQUIRED);
        return values.getFirst();
    }
    Optional<WorkoutSession> find(UUID owner, long generation, UUID id, boolean lock) {
        var query = entityManager.createQuery("select s from WorkoutSession s where s.userId=:owner and s.dataGeneration=:generation and s.id=:id", WorkoutSession.class)
                .setParameter("owner", owner).setParameter("generation", generation).setParameter("id", id);
        if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        return query.getResultStream().findFirst();
    }
    Optional<WorkoutSession> active(UUID owner, long generation) {
        return entityManager.createQuery("select s from WorkoutSession s where s.userId=:owner and s.dataGeneration=:generation and s.status='ACTIVE'", WorkoutSession.class)
                .setParameter("owner", owner).setParameter("generation", generation).getResultStream().findFirst();
    }
    boolean occupied(UUID id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE id=?)", Boolean.class, id);
    }
    void insert(WorkoutSession value) { entityManager.persist(value); }
    void flush() { entityManager.flush(); }
}
