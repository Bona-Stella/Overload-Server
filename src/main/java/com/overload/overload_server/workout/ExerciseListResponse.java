package com.overload.overload_server.workout;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.UUID;

public record ExerciseListResponse(UUID userId, @JsonFormat(shape = JsonFormat.Shape.STRING) long dataGeneration,
		List<Exercise> exercises) {

	public record Exercise(UUID id, String name) { }
}
