package com.overload.overload_server.workoutcommand;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** SQL guard for a caller-owned workout JPA transaction. No manager nesting or domain dispatcher. */
public final class WorkoutWriteGuard {

	private final JdbcTemplate jdbc;
	private final EntityManagerFactory factory;

	public WorkoutWriteGuard(JdbcTemplate jdbc, EntityManagerFactory factory) {
		this.jdbc = jdbc;
		this.factory = factory;
	}

	public static final class LockedOwner {
		private final UUID userId;
		private final Counter generation;
		private final EntityManager transaction;
		private LockedOwner(UUID userId, Counter generation, EntityManager transaction) {
			this.userId = userId; this.generation = generation; this.transaction = transaction;
		}
		public UUID userId() { return userId; }
		public Counter dataGeneration() { return generation; }
	}

	public LockedOwner lockOwner(SessionPrincipal principal, CommandEnvelope command) {
		if (principal == null) throw violation(WriteContractViolation.Code.AUTHENTICATION_REQUIRED);
		if (!principal.userId().equals(command.expectedUserId())) throw violation(WriteContractViolation.Code.OWNER_CONTEXT_MISMATCH);
		var transaction = transaction();
		var rows = jdbc.queryForList("SELECT data_generation FROM users WHERE id = ? FOR UPDATE", Long.class, principal.userId());
		if (rows.isEmpty()) throw violation(WriteContractViolation.Code.AUTHENTICATION_REQUIRED);
		if (rows.getFirst() != command.dataGeneration().longValue()) throw violation(WriteContractViolation.Code.DATA_GENERATION_MISMATCH);
		return new LockedOwner(principal.userId(), command.dataGeneration(), transaction);
	}

	public void requireUnusedExerciseId(LockedOwner owner, UUID id) {
		if (transaction() != owner.transaction) throw new IllegalStateException("Owner lock belongs to another transaction");
		CommandValues.newId(id);
		// Only existence is observed. Do not disclose another owner's identity or tombstone details.
		if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM exercise_definitions WHERE id = ?)", Boolean.class, id))) {
			throw violation(WriteContractViolation.Code.ID_UNAVAILABLE);
		}
	}

	private EntityManager transaction() {
		var holder = TransactionSynchronizationManager.getResource(factory);
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| TransactionSynchronizationManager.isCurrentTransactionReadOnly()
				|| !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
				|| !(holder instanceof EntityManagerHolder entityManager) || !entityManager.getEntityManager().isJoinedToTransaction()) {
			throw new IllegalStateException("Explicit READ_COMMITTED workout JPA write transaction required");
		}
		return entityManager.getEntityManager();
	}

	private static WriteContractViolation violation(WriteContractViolation.Code code) { return new WriteContractViolation(code); }
}
