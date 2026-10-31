package com.overload.overload_server.routine;

import com.overload.overload_server.exercise.ExerciseDefinition;
import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Aggregate-scoped persistence, always called inside workoutTransactionManager. */
@Repository
public class RoutineRepository {
	@PersistenceContext private EntityManager entityManager;
	private final JdbcTemplate jdbc;
	RoutineRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	long generation(UUID owner, boolean lock) {
		var values = jdbc.query("SELECT data_generation FROM users WHERE id=?" + (lock ? " FOR SHARE" : ""),
				(row, index) -> row.getLong(1), owner);
		if (values.isEmpty()) throw new RoutineFailure(RoutineFailure.Reason.AUTHENTICATION_REQUIRED);
		return values.getFirst();
	}
	List<Routine> list(UUID owner, long generation) {
		return entityManager.createQuery("select r from Routine r where r.userId=:owner and r.dataGeneration=:generation and r.deletedAt is null order by r.id", Routine.class)
				.setParameter("owner", owner).setParameter("generation", generation).getResultList();
	}
	Routine find(UUID owner, long generation, UUID id, boolean lock) {
		var query = entityManager.createQuery("select r from Routine r where r.userId=:owner and r.dataGeneration=:generation and r.id=:id", Routine.class)
				.setParameter("owner", owner).setParameter("generation", generation).setParameter("id", id);
		if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
		return query.getResultStream().findFirst().orElseThrow(() -> new RoutineFailure(RoutineFailure.Reason.ROUTINE_NOT_FOUND));
	}
	boolean occupied(String table, UUID id) {
		// Only fixed internal table names supplied by this aggregate service, never request strings.
		return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id=?)", Boolean.class, id);
	}
	List<RoutineWeightPlacement> weights(Routine routine) {
		return entityManager.createQuery("select p from RoutineWeightPlacement p where p.userId=:owner and p.dataGeneration=:generation and p.routineId=:id order by p.position,p.id", RoutineWeightPlacement.class)
				.setParameter("owner", routine.userId).setParameter("generation", routine.dataGeneration).setParameter("id", routine.id).getResultList();
	}
	List<RoutineCardioPlacement> cardio(Routine routine) {
		return entityManager.createQuery("select p from RoutineCardioPlacement p where p.userId=:owner and p.dataGeneration=:generation and p.routineId=:id order by p.position,p.id", RoutineCardioPlacement.class)
				.setParameter("owner", routine.userId).setParameter("generation", routine.dataGeneration).setParameter("id", routine.id).getResultList();
	}
	Map<UUID, ExerciseDefinition> exercises(UUID owner, long generation, Set<UUID> ids, boolean lock) {
		if (ids.isEmpty()) return Map.of();
		var query = entityManager.createQuery("select e from ExerciseDefinition e where e.userId=:owner and e.dataGeneration=:generation and e.id in :ids order by e.id", ExerciseDefinition.class)
				.setParameter("owner", owner).setParameter("generation", generation).setParameter("ids", ids);
		if (lock) query.setLockMode(LockModeType.PESSIMISTIC_READ);
		var result = new HashMap<UUID, ExerciseDefinition>();
		query.getResultList().forEach(value -> result.put(value.getId(), value));
		return result;
	}
	void insert(Object value) { entityManager.persist(value); }
	void flush() { entityManager.flush(); }
}
