package com.overload.overload_server.security;

import jakarta.servlet.http.HttpSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class SessionPolicy {

	public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(90);
	public static final Duration RECENT_REAUTHENTICATION = Duration.ofMinutes(10);

	private final Clock clock;

	public SessionPolicy(Clock clock) {
		this.clock = clock;
	}

	public Instant now() {
		return clock.instant();
	}

	public Optional<SessionMetadata> activeMetadata(HttpSession session) {
		if (session == null || !(session.getAttribute(SessionMetadata.ATTRIBUTE) instanceof SessionMetadata metadata)) {
			return Optional.empty();
		}
		var now = now();
		if (metadata.userId() == null || metadata.loginStartedAt() == null || metadata.absoluteExpiresAt() == null
				|| metadata.reauthenticatedAt() == null
				|| metadata.loginStartedAt().isAfter(now)
				|| !metadata.absoluteExpiresAt().equals(metadata.loginStartedAt().plus(ABSOLUTE_LIFETIME))
				|| !now.isBefore(metadata.absoluteExpiresAt())
				|| metadata.reauthenticatedAt().isBefore(metadata.loginStartedAt())
				|| metadata.reauthenticatedAt().isAfter(now)) {
			return Optional.empty();
		}
		return Optional.of(metadata);
	}

	public boolean hasRecentReauthentication(HttpSession session) {
		return activeMetadata(session).map(metadata ->
				!metadata.reauthenticatedAt().isBefore(now().minus(RECENT_REAUTHENTICATION))).orElse(false);
	}

	public int remainingSeconds(SessionMetadata metadata) {
		return (int) Math.max(0, Duration.between(now(), metadata.absoluteExpiresAt()).getSeconds());
	}

}
