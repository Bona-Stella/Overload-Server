package com.overload.overload_server.workoutsettings;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Transactional(transactionManager = "workoutTransactionManager", readOnly = true)
public interface UserWorkoutSettingsRepository extends Repository<UserWorkoutSettings, UUID> {

	Optional<UserWorkoutSettings> findByUserIdAndDataGeneration(UUID userId, long dataGeneration);

	@Transactional(transactionManager = "workoutTransactionManager")
	void flush();
}
