package com.overload.testfixture;

import com.overload.overload_server.security.AuthenticationSessionService;
import com.overload.overload_server.security.SecurityConfiguration;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.security.SessionPolicy;
import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Explicitly imported only in tests, outside the application's component-scan package. */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityFixtures {

	public static final UUID USER_ID = UUID.randomUUID();
	public static final MutableClock CLOCK = new MutableClock();

	@Bean
	@Primary
	Clock fixtureClock() {
		return CLOCK;
	}

	@Bean
	@Order(0)
	SecurityFilterChain fixtureLoginChain(HttpSecurity http, SecurityConfiguration configuration,
			SessionPolicy policy, HttpSessionCsrfTokenRepository csrf, HttpSessionSecurityContextRepository contexts)
			throws Exception {
		http.securityMatcher("/__test__/login")
				.authorizeHttpRequests(authorize -> authorize.requestMatchers("/__test__/login").permitAll());
		// Same production CSRF, expiry, session and exception filters; only this test URL permits anonymous login.
		return configuration.securityFilterChain(http, policy, csrf, contexts);
	}

	@RestController
	public static class FixtureController {
		private final AuthenticationSessionService sessions;
		private final SessionPolicy policy;

		FixtureController(AuthenticationSessionService sessions, SessionPolicy policy) {
			this.sessions = sessions;
			this.policy = policy;
		}

		@PostMapping("/__test__/login")
		ResponseEntity<Void> login(HttpServletRequest request, HttpServletResponse response) {
			sessions.establishAfterVerification(request, response, USER_ID);
			return ResponseEntity.noContent().build();
		}

		@PostMapping("/__test__/reauthenticate")
		ResponseEntity<Void> reauthenticate(HttpServletRequest request, HttpServletResponse response) {
			sessions.reauthenticateAfterVerification(request, response, USER_ID);
			return ResponseEntity.noContent().build();
		}

		@PostMapping("/__test__/rotate")
		ResponseEntity<Void> rotate(HttpServletRequest request) {
			request.changeSessionId();
			return ResponseEntity.noContent().build();
		}

		@PostMapping("/__test__/mutate")
		ResponseEntity<Void> mutate() {
			return ResponseEntity.noContent().build();
		}

		@GetMapping("/__test__/me")
		Map<String, Object> me(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
			var metadata = (SessionMetadata) request.getSession().getAttribute(SessionMetadata.ATTRIBUTE);
			return Map.of("userId", principal.userId(), "loginStartedAt", metadata.loginStartedAt(),
					"absoluteExpiresAt", metadata.absoluteExpiresAt(), "reauthenticatedAt", metadata.reauthenticatedAt());
		}

		@GetMapping("/__test__/sensitive")
		ResponseEntity<Void> sensitive(HttpServletRequest request) {
			return ResponseEntity.status(policy.hasRecentReauthentication(request.getSession(false)) ? 204 : 403).build();
		}

		@GetMapping("/__test__/denied")
		void denied() {
			throw new AccessDeniedException("test authorization denial");
		}
	}

	public static final class MutableClock extends Clock {
		private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.now());

		public void reset() {
			instant.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
		}

		public void advance(Duration duration) {
			instant.updateAndGet(value -> value.plus(duration));
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant.get();
		}
	}

}
