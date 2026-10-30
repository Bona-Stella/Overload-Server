package com.overload.overload_server.exercise;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Transactional(transactionManager = "workoutTransactionManager", readOnly = true)
public interface ExerciseDefinitionRepository extends Repository<ExerciseDefinition, UUID> {

	List<ExerciseDefinition> findAllByUserIdAndDataGenerationOrderById(UUID userId, long dataGeneration);
	Optional<ExerciseDefinition> findByIdAndUserIdAndDataGeneration(UUID id, UUID userId, long dataGeneration);
}
