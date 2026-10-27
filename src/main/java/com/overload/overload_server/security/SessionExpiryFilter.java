package com.overload.overload_server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class SessionExpiryFilter extends OncePerRequestFilter {

	private final SessionPolicy policy;

	SessionExpiryFilter(SessionPolicy policy) {
		this.policy = policy;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)) {
			var session = request.getSession(false);
			var metadata = policy.activeMetadata(session);
			if (!(authentication.getPrincipal() instanceof SessionPrincipal principal) || metadata.isEmpty()
					|| !metadata.get().userId().equals(principal.userId())) {
				if (session != null) {
					session.invalidate();
				}
				SecurityContextHolder.clearContext();
			} else {
				// Store cleanup TTL is bounded by the original deadline, never a new 90-day idle window.
				session.setMaxInactiveInterval(Math.max(1, policy.remainingSeconds(metadata.get())));
			}
		}
		chain.doFilter(request, response);
	}

}
