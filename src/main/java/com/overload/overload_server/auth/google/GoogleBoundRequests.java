package com.overload.overload_server.auth.google;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.security.SessionPolicy;
import com.overload.overload_server.security.SessionReauthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

abstract class GoogleBoundRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
	private final String attribute;
	protected final String claimed;
	private final String started;
	private final String purpose;
	protected final JdbcTemplate jdbc;
	protected final TransactionTemplate transaction;
	protected final AccountIdentityService identities;
	protected final SessionReauthentication sessions;
	protected final SessionPolicy policy;

	GoogleBoundRequests(String purpose, JdbcTemplate jdbc, PlatformTransactionManager manager, AccountIdentityService identities,
			SessionReauthentication sessions, SessionPolicy policy) {
		this.purpose=purpose; this.attribute=getClass().getName()+".pending"; this.claimed=attribute+".claimed"; this.started=attribute+".started";
		this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
		this.identities = identities; this.sessions = sessions; this.policy = policy;
	}

	abstract void verified(HttpServletRequest request, HttpServletResponse response, OidcUser user);

	void begin(HttpServletRequest request, SessionReauthentication.Snapshot snapshot, UUID identity) {
		if (identity == null || identities.google(snapshot.userId(), identity, false).isEmpty()) { throw new IdentityNotFound(); }
		request.setAttribute(started, new Pending(purpose, snapshot, identity, policy.now(), null));
	}
	protected void startPending(HttpServletRequest request,SessionReauthentication.Snapshot snapshot,
			com.overload.overload_server.security.EmailOwnershipProof proof,long version){
		request.setAttribute(started,new Pending(purpose,snapshot,null,policy.now(),null,proof,version));
	}
	protected void validatePending(Pending pending){validateOwner(pending);}
	private void validateBound(Pending pending){
		if(!purpose.equals(pending.purpose())){throw new SessionReauthentication.SessionChanged();}
		validatePending(pending);
	}

	@Override public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		var session = request.getSession(false);
		if (session == null) { return null; }
		var pending = read(session.getId());
		return pending != null && matches(pending, request.getParameter("state")) ? pending.authorization() : null;
	}

	@Override public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request, HttpServletResponse response) {
		try { save(authorization, request); }
		catch (RuntimeException failure) {
			// The redirect filter can log this exception. Never attach JDBC values or provider/session details.
			throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
					new org.springframework.security.oauth2.core.OAuth2Error("google_reauthentication_unavailable"));
		}
	}

	private void save(OAuth2AuthorizationRequest authorization, HttpServletRequest request) {
		if (authorization == null) { return; }
		if (!(request.getAttribute(started) instanceof Pending started)) { throw new SessionReauthentication.SessionChanged(); }
		var pending = new Pending(started.purpose(), started.snapshot(), started.identity(), started.startedAt(), authorization,started.emailProof(),started.identityVersion());
		transaction.executeWithoutResult(status -> {
			validateBound(pending); sessions.validate(pending.snapshot());
			jdbc.update("""
					INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
					SELECT primary_id, ?, ? FROM spring_session WHERE session_id = ?
					ON CONFLICT (session_primary_id, attribute_name) DO UPDATE SET attribute_bytes = EXCLUDED.attribute_bytes
					""", attribute, new SerializingConverter().convert(pending), pending.snapshot().sessionId());
			var stored = read(pending.snapshot().sessionId());
			if (stored == null || !matches(stored, authorization.getState()) || !stored.snapshot().equals(pending.snapshot())) {
				throw new SessionReauthentication.SessionChanged();
			}
		});
	}

	@Override public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
		var snapshot = (SessionReauthentication.Snapshot) request.getAttribute(GoogleBoundOidcSecurity.SNAPSHOT);
		if (snapshot == null) { return null; }
		var pending = transaction.execute(status -> {
			// Read the identity hint first, then identity -> session locks, matching email REAUTH lock order.
			var hint = read(snapshot.sessionId());
			if (hint == null || !matches(hint, request.getParameter("state")) || !hint.snapshot().equals(snapshot)) { return null; }
			validateBound(hint); sessions.validate(snapshot);
			var current = read(snapshot.sessionId());
			if (current == null || !matches(current, request.getParameter("state")) || !current.snapshot().equals(snapshot)) { return null; }
			int consumed = jdbc.update("""
					DELETE FROM spring_session_attributes WHERE attribute_name = ?
					AND session_primary_id = (SELECT primary_id FROM spring_session WHERE session_id = ?)
					""", attribute, snapshot.sessionId());
			if (consumed != 1 || read(snapshot.sessionId()) != null) { throw new SessionReauthentication.SessionChanged(); }
			return current;
		});
		if (pending == null) { return null; }
		request.setAttribute(claimed, pending);
		// Consumption is committed before any provider network exchange; even expiry/cancellation cannot replay it.
		GoogleAuthenticationTime.validateRequest(pending.startedAt(), policy.now());
		return pending.authorization();
	}

	protected AccountIdentityService.GoogleIdentity validateOwner(Pending pending) {
		if (!purpose.equals(pending.purpose())) { throw new SessionReauthentication.SessionChanged(); }
		return identities.google(pending.snapshot().userId(), pending.identity(), true).orElseThrow(IdentityNotFound::new);
	}
	private Pending read(String sessionId) {
		var values = jdbc.queryForList("""
				SELECT a.attribute_bytes FROM spring_session_attributes a JOIN spring_session s ON s.primary_id=a.session_primary_id
				WHERE s.session_id=? AND a.attribute_name=?
				""", byte[].class, sessionId, attribute);
		return !values.isEmpty() && new DeserializingConverter().convert(values.getFirst()) instanceof Pending pending ? pending : null;
	}
	private static boolean matches(Pending pending, String state) {
		return pending.authorization() != null && state != null && MessageDigest.isEqual(
				pending.authorization().getState().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8));
	}
	protected record Pending(String purpose, SessionReauthentication.Snapshot snapshot, UUID identity, Instant startedAt,
			OAuth2AuthorizationRequest authorization,com.overload.overload_server.security.EmailOwnershipProof emailProof,long identityVersion) implements Serializable {
		Pending(String purpose,SessionReauthentication.Snapshot snapshot,UUID identity,Instant startedAt,OAuth2AuthorizationRequest authorization){this(purpose,snapshot,identity,startedAt,authorization,null,0);}
		@Override public String toString() { return "GoogleReauthenticationPending[redacted]"; }
	}
	static final class IdentityNotFound extends RuntimeException { }
}
