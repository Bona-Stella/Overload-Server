package com.overload.overload_server.workout;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.UUID;

public record WorkoutSettingsResponse(UUID userId, @JsonFormat(shape = JsonFormat.Shape.STRING) long dataGeneration,
		int restLowSeconds, int restMediumSeconds, int restHighSeconds) { }
