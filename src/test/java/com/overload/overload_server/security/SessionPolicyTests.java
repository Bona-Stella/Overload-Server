package com.overload.overload_server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionPolicyTests {

	private static final Instant START = Instant.parse("2026-10-27T00:00:00Z");
	private static final UUID USER = UUID.randomUUID();

	@Test
	void absoluteDeadlineExcludesTheExactNinetiethDay() {
		var session = session(new SessionMetadata(USER, START, START.plus(Duration.ofDays(90)), START));
		assertThat(at(START.plus(Duration.ofDays(90)).minusNanos(1)).activeMetadata(session)).isPresent();
		assertThat(at(START.plus(Duration.ofDays(90))).activeMetadata(session)).isEmpty();
	}

	@Test
	void rejectsMissingMalformedFutureAndArtificiallyExtendedMetadata() {
		var policy = at(START);
		assertThat(policy.activeMetadata(null)).isEmpty();
		assertThat(policy.activeMetadata(new MockHttpSession())).isEmpty();
		for (var metadata : new SessionMetadata[]{
				new SessionMetadata(null, START, START.plus(Duration.ofDays(90)), START),
				new SessionMetadata(USER, START, START.plus(Duration.ofDays(91)), START),
				new SessionMetadata(USER, START.plusSeconds(1), START.plus(Duration.ofDays(90)).plusSeconds(1), START),
				new SessionMetadata(USER, START, START.plus(Duration.ofDays(90)), START.plusSeconds(1)),
				new SessionMetadata(USER, START, START.plus(Duration.ofDays(90)), START.minusSeconds(1))}) {
			assertThat(policy.activeMetadata(session(metadata))).isEmpty();
		}
	}

	@Test
	void recentProofIncludesExactlyTenMinutesButCannotOutliveTheSession() {
		var session = session(new SessionMetadata(USER, START, START.plus(Duration.ofDays(90)), START));
		assertThat(at(START.plusSeconds(600)).hasRecentReauthentication(session)).isTrue();
		assertThat(at(START.plusSeconds(600).plusNanos(1)).hasRecentReauthentication(session)).isFalse();
		var expiry = START.plus(Duration.ofDays(90));
		assertThat(at(expiry).hasRecentReauthentication(session(
				new SessionMetadata(USER, START, expiry, expiry.minusSeconds(1))))).isFalse();
	}

	private static SessionPolicy at(Instant instant) {
		return new SessionPolicy(Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static MockHttpSession session(SessionMetadata metadata) {
		var session = new MockHttpSession();
		session.setAttribute(SessionMetadata.ATTRIBUTE, metadata);
		return session;
	}

}
