package com.overload.overload_server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionReauthentication {
	static final String COOKIE = SessionReauthentication.class.getName() + ".cookie";
	private final ReauthenticationSessionRepository repository;
	private final SessionRepository<? extends Session> sessions;
	private final SessionPolicy policy;
	private final CookieSerializer cookies;

	public SessionReauthentication(ReauthenticationSessionRepository repository, SessionRepository<? extends Session> sessions,
			SessionPolicy policy, CookieSerializer cookies) {
		this.repository = repository; this.sessions = sessions; this.policy = policy; this.cookies = cookies;
	}

	public Snapshot capture(HttpServletRequest request, UUID user) {
		var session = request.getSession(false);
		var metadata = policy.activeMetadata(session).orElseThrow(SessionChanged::new);
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!user.equals(metadata.userId()) || authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof SessionPrincipal principal) || !user.equals(principal.userId())) {
			throw new SessionChanged();
		}
		return new Snapshot(session.getId(), user, metadata);
	}

	public void validate(Snapshot expected) { repository.validate(expected); }
	public Rotation rotate(Snapshot expected) { return repository.rotate(expected); }
	public Rotation rotate(Snapshot expected, java.time.Instant authenticatedAt) { return repository.rotate(expected, authenticatedAt); }
	public void validateRotation(Rotation rotation) { repository.validateRotation(rotation); }

	public void complete(HttpServletRequest request, HttpServletResponse response, Rotation rotation) {
		complete(request, response, rotation, () -> { });
	}

	public void complete(HttpServletRequest request, HttpServletResponse response, Rotation rotation, Runnable validateEvidence) {
		try {
			if (!capture(request, rotation.original().userId()).equals(rotation.original())) { throw new SessionChanged(); }
			validateEvidence.run();
			verifyStored(rotation);
			request.setAttribute(COOKIE, new ReauthenticationCookie(rotation, this, validateEvidence));
			cookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, rotation.sessionId()));
		} catch (RuntimeException failure) {
			discard(rotation);
			throw failure;
		}
	}

	void verifyStored(Rotation rotation) {
		var stored = sessions.findById(rotation.sessionId());
		if (stored == null || !policy.isActive(rotation.metadata())
				|| !rotation.metadata().equals(stored.getAttribute(SessionMetadata.ATTRIBUTE))
				|| !(stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) instanceof SecurityContext context)
				|| context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()
				|| !(context.getAuthentication().getPrincipal() instanceof SessionPrincipal principal)
				|| !rotation.original().userId().equals(principal.userId())) { throw new SessionChanged(); }
	}

	void discard(Rotation rotation) {
		SecurityContextHolder.clearContext();
		try { sessions.deleteById(rotation.sessionId()); }
		catch (RuntimeException unavailable) { /* Fail closed; never report a successful store. */ }
	}

	void finishResponse(HttpServletRequest request, HttpServletResponse response, ReauthenticationCookie marker) {
		if (response.getStatus() < 200 || response.getStatus() >= 400) { return; }
		var rotation = marker.rotation();
		try { marker.verify(); }
		catch (RuntimeException failure) {
			discard(rotation);
			response.setStatus(failure instanceof SessionChanged ? 401 : 503);
			response.setHeader("Location", null);
			var expired = new CookieSerializer.CookieValue(request, response, "");
			expired.setCookieMaxAge(0);
			cookies.writeCookieValue(expired);
		}
	}

	public record Snapshot(String sessionId, UUID userId, SessionMetadata metadata) implements java.io.Serializable {
		@Override public String toString() { return "ReauthenticationSnapshot[redacted]"; }
	}
	public record Rotation(Snapshot original, String sessionId, SessionMetadata metadata) {
		@Override public String toString() { return "ReauthenticationRotation[redacted]"; }
	}
	public static class SessionChanged extends RuntimeException {
		public SessionChanged() { super("Authentication session changed"); }
	}
	record ReauthenticationCookie(Rotation rotation, SessionReauthentication owner, Runnable validateEvidence) {
		void verify() { validateEvidence.run(); owner.verifyStored(rotation); validateEvidence.run(); }
		@Override public String toString() { return "ReauthenticationCookie[redacted]"; }
	}
}
