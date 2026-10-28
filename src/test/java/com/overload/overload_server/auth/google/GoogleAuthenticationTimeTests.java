package com.overload.overload_server.auth.google;

import com.overload.overload_server.security.SessionReauthentication;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class GoogleAuthenticationTimeTests {
	private final Instant start = Instant.parse("2026-10-28T00:00:00.900Z");

	@Test void secondsPrecisionDoesNotInventAPastSixtySecondAllowance() {
		assertThatCode(() -> GoogleAuthenticationTime.validate(start, start.minusMillis(900), start)).doesNotThrowAnyException();
		assertThatThrownBy(() -> GoogleAuthenticationTime.validate(start, start.minusMillis(901), start))
				.isInstanceOf(SessionReauthentication.SessionChanged.class);
	}
	@Test void reversedClockAndExpiredRequestFailClosed() {
		assertThatThrownBy(() -> GoogleAuthenticationTime.validate(start, start, start.minusNanos(1)))
				.isInstanceOf(SessionReauthentication.SessionChanged.class);
		assertThatThrownBy(() -> GoogleAuthenticationTime.validate(start, start, start.plusSeconds(300)))
				.isInstanceOf(SessionReauthentication.SessionChanged.class);
	}
	@Test void futureSkewHasAnExactSixtySecondLimitAndMissingTimeNeverPasses() {
		assertThatCode(() -> GoogleAuthenticationTime.validate(start, start.plusSeconds(60), start)).doesNotThrowAnyException();
		assertThatThrownBy(() -> GoogleAuthenticationTime.validate(start, start.plusSeconds(60).plusNanos(1), start))
				.isInstanceOf(SessionReauthentication.SessionChanged.class);
		assertThatThrownBy(() -> GoogleAuthenticationTime.validate(start, null, start))
				.isInstanceOf(SessionReauthentication.SessionChanged.class);
	}
}
