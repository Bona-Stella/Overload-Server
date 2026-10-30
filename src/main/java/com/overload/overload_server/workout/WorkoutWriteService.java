package com.overload.overload_server.workout;

import com.overload.overload_server.exercise.ExerciseDefinition;
import com.overload.overload_server.exercise.ExerciseDefinitionRepository;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class WorkoutWriteService {

	private final WorkoutOwnerRepository owners;
	private final ExerciseDefinitionRepository exercises;
	private final UserWorkoutSettingsRepository settings;
	private final TransactionTemplate transaction;

	WorkoutWriteService(WorkoutOwnerRepository owners, ExerciseDefinitionRepository exercises,
			UserWorkoutSettingsRepository settings,
			@Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
		this.owners = owners;
		this.exercises = exercises;
		this.settings = settings;
		this.transaction = new TransactionTemplate(manager);
	}

	public record Creation(boolean created, ExerciseWriteResponse exercise) { }

	public Creation create(UUID userId, WorkoutWriteRequests.CreateExercise request) {
		return write(() -> {
			owner(userId, request.dataGeneration());
			var existing = exercises.findByIdAndUserIdAndDataGeneration(request.id(), userId, request.dataGeneration());
			if (existing.isPresent()) {
				var value = existing.get();
				if (value.getDeletedAt() != null || !value.getName().equals(request.name())) {
					throw new WorkoutWriteFailure(WorkoutWriteFailure.Reason.EXERCISE_ID_CONFLICT);
				}
				return new Creation(false, response(value));
			}
			var value = exercises.saveAndFlush(ExerciseDefinition.create(request.id(), userId,
					request.dataGeneration(), request.name(), Instant.now()));
			return new Creation(true, response(value));
		});
	}

	public ExerciseWriteResponse rename(UUID userId, UUID id, WorkoutWriteRequests.RenameExercise request) {
		return write(() -> {
			owner(userId, request.dataGeneration());
			var value = exercises.findByIdAndUserIdAndDataGeneration(id, userId, request.dataGeneration())
					.filter(exercise -> exercise.getDeletedAt() == null)
					.orElseThrow(() -> new WorkoutWriteFailure(WorkoutWriteFailure.Reason.EXERCISE_NOT_FOUND));
			value.rename(request.name(), Instant.now());
			exercises.flush();
			return response(value);
		});
	}

	public WorkoutSettingsResponse changeRest(UUID userId, WorkoutWriteRequests.ChangeRest request) {
		return write(() -> {
			owner(userId, request.dataGeneration());
			var value = settings.findByUserIdAndDataGeneration(userId, request.dataGeneration())
					.orElseThrow(() -> new WorkoutWriteFailure(WorkoutWriteFailure.Reason.WORKOUT_SETTINGS_MISSING));
			value.changeRest(request.restLowSeconds(), request.restMediumSeconds(), request.restHighSeconds(), Instant.now());
			settings.flush();
			return new WorkoutSettingsResponse(userId, request.dataGeneration(), value.getRestLowSeconds(),
					value.getRestMediumSeconds(), value.getRestHighSeconds());
		});
	}

	private void owner(UUID userId, long expected) {
		long actual = owners.findGenerationForWrite(userId)
				.orElseThrow(() -> new WorkoutWriteFailure(WorkoutWriteFailure.Reason.AUTHENTICATION_REQUIRED));
		if (actual != expected) throw new WorkoutWriteFailure(WorkoutWriteFailure.Reason.DATA_GENERATION_MISMATCH);
	}

	private static ExerciseWriteResponse response(ExerciseDefinition value) {
		return new ExerciseWriteResponse(value.getUserId(), value.getDataGeneration(), value.getId(), value.getName());
	}

	private <T> T write(Supplier<T> action) {
		var original = new AtomicReference<RuntimeException>();
		try {
			return transaction.execute(status -> {
				try { return action.get(); }
				catch (RuntimeException failure) { original.set(failure); throw failure; }
			});
		} catch (RuntimeException failure) {
			// As in reads, a disconnected connection's rollback must not hide the original failure.
			if (original.get() != null && original.get() != failure) failure.addSuppressed(original.get());
			throw failure;
		}
	}
}
