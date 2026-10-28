package com.overload.overload_server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Internal boundary after a real authentication proof and successful account provisioning. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class VerifiedSessionLogin {

	private final AuthenticationSessionService sessions;
	private final SessionRepository<? extends Session> storedSessions;
	private final SessionPolicy policy;

	public VerifiedSessionLogin(AuthenticationSessionService sessions, SessionRepository<? extends Session> storedSessions,
			SessionPolicy policy) {
		this.sessions = sessions;
		this.storedSessions = storedSessions;
		this.policy = policy;
	}

	public void establish(HttpServletRequest request, HttpServletResponse response, UUID verifiedUserId) {
		establish(request,response,verifiedUserId,null);
	}

	public void establish(HttpServletRequest request, HttpServletResponse response, UUID verifiedUserId,
			EmailOwnershipProof.Verification emailVerification) {
		try {
			// A fresh JDBC primary key prevents late anonymous writes from undoing ID rotation.
			if (request.getSession(false) != null) {
				request.getSession(false).invalidate();
			}
			sessions.establishAfterVerification(request, response, verifiedUserId);
			var proof=emailVerification==null?null:emailVerification.bind(verifiedUserId,request.getSession().getId());
			if(proof!=null){request.getSession().setAttribute(EmailOwnershipProof.ATTRIBUTE,proof);}
			var expected = policy.activeMetadata(request.getSession(false)).orElseThrow(IllegalStateException::new);
			var stored = storedSessions.findById(request.getSession().getId());
			// Session JDBC can suppress integrity failures as concurrent deletion: verify the durable result.
			if (stored == null
					|| !(stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) instanceof SecurityContext context)
					|| context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()
					|| !(context.getAuthentication().getPrincipal() instanceof SessionPrincipal principal)
					|| !principal.userId().equals(verifiedUserId)
					|| !(stored.getAttribute(SessionMetadata.ATTRIBUTE) instanceof SessionMetadata metadata)
					|| !metadata.equals(expected)
					|| !java.util.Objects.equals(proof,stored.getAttribute(EmailOwnershipProof.ATTRIBUTE))) {
				throw new IllegalStateException();
			}
		} catch (RuntimeException exception) {
			SecurityContextHolder.clearContext();
			try {
				if (request.getSession(false) != null) {
					request.getSession(false).invalidate();
				}
			} catch (RuntimeException cleanupFailure) {
				// Fail closed even while the store is unavailable; never acknowledge successful authentication.
			}
			throw new IllegalStateException("Verified session could not be persisted");
		}
	}

}
