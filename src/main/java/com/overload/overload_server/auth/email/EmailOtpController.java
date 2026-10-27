package com.overload.overload_server.auth.email;

import com.overload.overload_server.security.AuthenticationSessionService;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EmailOtpController {

	private static final String BROWSER_NONCE = EmailOtpController.class.getName() + ".browserNonce";
	private final EmailOtpService otp;
	private final AuthenticationSessionService sessions;
	private final SessionRepository<? extends Session> storedSessions;

	EmailOtpController(EmailOtpService otp, AuthenticationSessionService sessions,
			SessionRepository<? extends Session> storedSessions) {
		this.otp = otp;
		this.sessions = sessions;
		this.storedSessions = storedSessions;
	}

	@PostMapping("/api/v1/auth/email-otp/request")
	ResponseEntity<Map<String, Object>> request(@RequestBody Request body, HttpServletRequest request) {
		requireAnonymous();
		var id = otp.request(body.email(), request.getRemoteAddr(), browserNonce(request));
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
				.body(Map.of("status", "ACCEPTED", "challengeId", id));
	}

	@PostMapping("/api/v1/auth/email-otp/verify")
	ResponseEntity<Void> verify(@RequestBody Verification body, HttpServletRequest request, HttpServletResponse response) {
		requireAnonymous();
		var account = otp.verify(body.email(), body.challengeId(), body.code(), request.getRemoteAddr(), browserNonce(request));
		try {
			// A new JDBC session primary key prevents late anonymous requests from undoing ID rotation.
			// The OTP has already consumed the browser-bound proof; no anonymous attributes need carrying over.
			if (request.getSession(false) != null) {
				request.getSession(false).invalidate();
			}
			sessions.establishAfterVerification(request, response, account.id());
			// Session JDBC may suppress some integrity errors as a concurrent deletion. Confirm the durable result.
			var stored = storedSessions.findById(request.getSession().getId());
			if (stored == null
					|| !(stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) instanceof SecurityContext context)
					|| context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()
					|| !(context.getAuthentication().getPrincipal() instanceof SessionPrincipal principal)
					|| !principal.userId().equals(account.id())
					|| !(stored.getAttribute(SessionMetadata.ATTRIBUTE) instanceof SessionMetadata metadata)
					|| !metadata.userId().equals(account.id())) {
				throw new OtpFailure(503, "OTP_UNAVAILABLE");
			}
		} catch (RuntimeException exception) {
			SecurityContextHolder.clearContext();
			if (request.getSession(false) != null) {
				request.getSession(false).invalidate();
			}
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static void requireAnonymous() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
			throw new OtpFailure(409, "LOGOUT_REQUIRED");
		}
	}

	private static String browserNonce(HttpServletRequest request) {
		var session = request.getSession();
		synchronized (session) {
			var nonce = session.getAttribute(BROWSER_NONCE);
			if (!(nonce instanceof String)) {
				nonce = UUID.randomUUID().toString();
				session.setAttribute(BROWSER_NONCE, nonce);
			}
			return (String) nonce;
		}
	}

	public record Request(String email) {
		@Override public String toString() { return "OtpRequest[redacted]"; }
	}

	public record Verification(String email, UUID challengeId, String code) {
		@Override public String toString() { return "OtpVerification[redacted]"; }
	}

}
