package com.overload.overload_server.account;

public record WorkoutSettings(int restLowSeconds, int restMediumSeconds, int restHighSeconds) {

	public WorkoutSettings {
		if (restLowSeconds <= 0 || restMediumSeconds <= 0 || restHighSeconds <= 0) {
			throw new IllegalArgumentException("Rest durations must be positive seconds");
		}
	}

	public int restSecondsFor(int maximumReps) {
		if (maximumReps <= 0) {
			throw new IllegalArgumentException("Maximum repetitions must be positive");
		}
		return maximumReps <= 10 ? restLowSeconds : maximumReps <= 12 ? restMediumSeconds : restHighSeconds;
	}

}
