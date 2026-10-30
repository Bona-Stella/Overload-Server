package com.overload.overload_server.workout;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.UUID;

public record ExerciseWriteResponse(UUID userId, @JsonFormat(shape = JsonFormat.Shape.STRING) long dataGeneration,
		UUID id, String name) { }
