package com.overload.overload_server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.OnCommittedResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Validate at response commit too: a 204 can be flushed after the controller's store readback. */
final class ReauthenticationResponseFilter extends OncePerRequestFilter {
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var wrapped = new OnCommittedResponseWrapper(response) {
			private boolean checked;
			@Override protected void onResponseCommitted() {
				if (!checked && request.getAttribute(SessionReauthentication.COOKIE)
						instanceof SessionReauthentication.ReauthenticationCookie marker) {
					checked = true;
					marker.owner().finishResponse(request, this, marker);
				}
			}
		};
		chain.doFilter(request, wrapped);
		if (!wrapped.isCommitted() && request.getAttribute(SessionReauthentication.COOKIE) != null) { wrapped.flushBuffer(); }
	}
}
