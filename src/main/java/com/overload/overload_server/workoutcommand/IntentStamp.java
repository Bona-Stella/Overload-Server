package com.overload.overload_server.workoutcommand;

import java.time.Instant;
import java.util.UUID;

/** Resolution retries retain this original stamp; user confirmation creates a new one. */
public record IntentStamp(Instant occurredAt, UUID originOperationId) implements Comparable<IntentStamp> {

	public IntentStamp {
		CommandValues.instant(occurredAt);
		CommandValues.newId(originOperationId);
	}

	@Override public int compareTo(IntentStamp other) {
		int time = occurredAt.compareTo(other.occurredAt);
		return time != 0 ? time : originOperationId.toString().compareTo(other.originOperationId.toString());
	}
}
