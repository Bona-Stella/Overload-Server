package com.overload.overload_server.auth.google;

import com.overload.overload_server.security.SessionReauthentication;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import java.time.Duration;
import java.time.Instant;

/** iat, consent and account selection are never evidence of fresh Google authentication. */
final class GoogleAuthenticationTime {
	static final Duration REQUEST_TTL = Duration.ofMinutes(5);
	static final Duration FUTURE_SKEW = Duration.ofSeconds(60);

	static JwtDecoderFactory<ClientRegistration> decoders() {
		var factory = new OidcIdTokenDecoderFactory();
		factory.setClaimTypeConverterFactory(client -> claims -> {
			var value = claims.get("auth_time");
			// Reject coercible strings and fractional numbers BEFORE the framework converts dates to Instant.
			if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 0) {
				throw new OAuth2AuthenticationException(new OAuth2Error("invalid_authentication_time"));
			}
			return OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter().convert(claims);
		});
		return factory;
	}

	static void validate(Instant started, Instant authenticationTime, Instant now) {
		validateRequest(started, now);
		if (authenticationTime == null
				|| authenticationTime.isBefore(started.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
				|| authenticationTime.isAfter(now.plus(FUTURE_SKEW))) {
			throw new SessionReauthentication.SessionChanged();
		}
	}

	static void validateRequest(Instant started, Instant now) {
		if (now.isBefore(started) || !now.isBefore(started.plus(REQUEST_TTL))) {
			throw new SessionReauthentication.SessionChanged();
		}
	}
}
