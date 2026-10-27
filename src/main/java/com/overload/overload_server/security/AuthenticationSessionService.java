package com.overload.overload_server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal post-verification boundary. This service never verifies credentials or accepts request DTOs. */
public class AuthenticationSessionService {

	private final SessionPolicy policy;
	private final SessionAuthenticationStrategy strategy;
	private final SecurityContextRepository contexts;

	public AuthenticationSessionService(SessionPolicy policy, SessionAuthenticationStrategy strategy,
			SecurityContextRepository contexts) {
		this.policy = policy;
		this.strategy = strategy;
		this.contexts = contexts;
	}

	public void establishAfterVerification(HttpServletRequest request, HttpServletResponse response, UUID userId) {
		Objects.requireNonNull(userId, "verified userId");
		var previous = policy.activeMetadata(request.getSession(false));
		if (request.getSession(false) != null && (previous.isEmpty() || !previous.get().userId().equals(userId))
				&& request.getSession(false).getAttribute(SessionMetadata.ATTRIBUTE) != null) {
			request.getSession(false).invalidate();
		}
		var now = policy.now();
		var metadata = previous.filter(value -> value.userId().equals(userId))
				.map(value -> new SessionMetadata(userId, value.loginStartedAt(), value.absoluteExpiresAt(), now))
				.orElseGet(() -> new SessionMetadata(userId, now, now.plus(SessionPolicy.ABSOLUTE_LIFETIME), now));
		var authentication = UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(userId), null,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		request.getSession();
		strategy.onAuthentication(authentication, request, response);
		var session = request.getSession();
		session.setAttribute(SessionMetadata.ATTRIBUTE, metadata);
		session.setMaxInactiveInterval(Math.max(1, policy.remainingSeconds(metadata)));
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
	}

	public void reauthenticateAfterVerification(HttpServletRequest request, HttpServletResponse response, UUID userId) {
		var metadata = policy.activeMetadata(request.getSession(false))
				.orElseThrow(() -> new IllegalStateException("An active authenticated session is required"));
		if (!metadata.userId().equals(userId)) {
			throw new IllegalArgumentException("Reauthentication must prove the same account");
		}
		establishAfterVerification(request, response, userId);
	}

}
