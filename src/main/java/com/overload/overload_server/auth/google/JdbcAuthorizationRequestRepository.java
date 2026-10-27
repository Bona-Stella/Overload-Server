package com.overload.overload_server.auth.google;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Temporary request uses Spring Session serialization; a JDBC row lock makes callback consumption single-use. */
final class JdbcAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	static final String ATTRIBUTE = JdbcAuthorizationRequestRepository.class.getName() + ".request";
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final SessionRepository<? extends Session> sessions;
	private final DeserializingConverter deserializer = new DeserializingConverter();

	JdbcAuthorizationRequestRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
			SessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(manager);
		this.sessions = sessions;
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		var session = request.getSession(false);
		return session != null && session.getAttribute(ATTRIBUTE) instanceof OAuth2AuthorizationRequest pending
				&& matches(pending, request.getParameter("state")) ? pending : null;
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest pending, HttpServletRequest request,
			HttpServletResponse response) {
		try {
			if (pending == null) {
				if (request.getSession(false) != null) {
					request.getSession(false).removeAttribute(ATTRIBUTE);
				}
			} else {
				request.getSession().setAttribute(ATTRIBUTE, pending);
				var stored = sessions.findById(request.getSession().getId());
				if (stored == null || !(stored.getAttribute(ATTRIBUTE) instanceof OAuth2AuthorizationRequest saved)
						|| !matches(saved, pending.getState())) {
					throw new IllegalStateException();
				}
			}
		} catch (RuntimeException exception) {
			// The redirect filter may log exceptions: do not attach JDBC/session/request details.
			throw new OAuth2AuthenticationException(new OAuth2Error("google_login_unavailable"));
		}
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		var session = request.getSession(false);
		if (session == null) {
			return null;
		}
		try {
			var id = session.getId();
			var claimed = transaction.execute(status -> {
				var locked = jdbc.queryForList("""
						SELECT primary_id FROM spring_session
						WHERE session_id = ? AND principal_name IS NULL AND expiry_time > ? FOR UPDATE
						""", String.class, id, System.currentTimeMillis());
				if (locked.isEmpty()) {
					return null;
				}
				// Use Spring Session JDBC's default Spring Core converter on this same connection.
				// SessionRepository.findById owns REQUIRES_NEW and would exhaust a small connection pool here.
				var attributes = jdbc.queryForList("""
						SELECT attribute_bytes FROM spring_session_attributes
						WHERE session_primary_id = ? AND attribute_name = ?
						""", byte[].class, locked.getFirst(), ATTRIBUTE);
				if (attributes.isEmpty() || !(deserializer.convert(attributes.getFirst()) instanceof OAuth2AuthorizationRequest pending)
						|| !matches(pending, request.getParameter("state"))) {
					return null;
				}
				jdbc.update("DELETE FROM spring_session WHERE primary_id = ?", locked.getFirst());
				return pending;
			});
			if (claimed != null) {
				// The row deletion committed before the HTTP wrapper invalidation or any provider network call.
				session.invalidate();
			}
			return claimed;
		} catch (RuntimeException exception) {
			throw new OAuth2AuthenticationException(new OAuth2Error("google_login_unavailable"));
		}
	}

	private static boolean matches(OAuth2AuthorizationRequest pending, String state) {
		return state != null && MessageDigest.isEqual(pending.getState().getBytes(StandardCharsets.UTF_8),
				state.getBytes(StandardCharsets.UTF_8));
	}

}
