package com.overload.overload_server.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record SessionMetadata(UUID userId, Instant loginStartedAt, Instant absoluteExpiresAt,
		Instant reauthenticatedAt) implements Serializable {

	public static final String ATTRIBUTE = SessionMetadata.class.getName();

}
