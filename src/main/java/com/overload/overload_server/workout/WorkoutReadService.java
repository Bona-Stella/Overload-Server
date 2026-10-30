package com.overload.overload_server.workout;

import com.overload.overload_server.exercise.ExerciseDefinitionRepository;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class WorkoutReadService {

	private final WorkoutOwnerRepository owners;
	private final ExerciseDefinitionRepository exercises;
	private final UserWorkoutSettingsRepository settings;
	private final TransactionTemplate transaction;

	WorkoutReadService(WorkoutOwnerRepository owners, ExerciseDefinitionRepository exercises,
			UserWorkoutSettingsRepository settings,
			@Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
		this.owners = owners;
		this.exercises = exercises;
		this.settings = settings;
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setReadOnly(true);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}

	public ExerciseListResponse exercises(UUID userId) {
		return read(() -> {
			long generation = generation(userId);
			var items = exercises.findAllByUserIdAndDataGenerationOrderById(userId, generation).stream()
					.map(value -> new ExerciseListResponse.Exercise(value.getId(), value.getName())).toList();
			return new ExerciseListResponse(userId, generation, items);
		});
	}

	public WorkoutSettingsResponse settings(UUID userId) {
		return read(() -> {
			long generation = generation(userId);
			var value = settings.findByUserIdAndDataGeneration(userId, generation)
					.orElseThrow(() -> new WorkoutReadFailure(WorkoutReadFailure.Reason.WORKOUT_SETTINGS_MISSING));
			return new WorkoutSettingsResponse(userId, generation, value.getRestLowSeconds(),
					value.getRestMediumSeconds(), value.getRestHighSeconds());
		});
	}

	private <T> T read(Supplier<T> query) {
		var original = new AtomicReference<RuntimeException>();
		try {
			return transaction.execute(status -> {
				try { return query.get(); }
				catch (RuntimeException failure) { original.set(failure); throw failure; }
			});
		} catch (RuntimeException failure) {
			// Hibernate may fail rollback on a dead connection. Retain the actual read failure for classification.
			if (original.get() != null && original.get() != failure) failure.addSuppressed(original.get());
			throw failure;
		}
	}

	private long generation(UUID userId) {
		return owners.findGeneration(userId)
				.orElseThrow(() -> new WorkoutReadFailure(WorkoutReadFailure.Reason.AUTHENTICATION_REQUIRED));
	}
}
