package com.overload.overload_server.auth.email;

import com.overload.overload_server.security.VerifiedSessionLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
	private final VerifiedSessionLogin sessions;
	private final com.overload.overload_server.auth.identity.LoginAvailability availability;

	EmailOtpController(EmailOtpService otp, VerifiedSessionLogin sessions,com.overload.overload_server.auth.identity.LoginAvailability availability) {
		this.otp = otp;
		this.sessions = sessions;
		this.availability=availability;
	}

	@PostMapping("/api/v1/auth/email-otp/request")
	ResponseEntity<Map<String, Object>> request(@RequestBody Request body, HttpServletRequest request) {
		requireEnabled();
		requireAnonymous();
		var id = otp.request(body.email(), request.getRemoteAddr(), browserNonce(request));
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
				.body(Map.of("status", "ACCEPTED", "challengeId", id));
	}

	@PostMapping("/api/v1/auth/email-otp/verify")
	ResponseEntity<Void> verify(@RequestBody Verification body, HttpServletRequest request, HttpServletResponse response) {
		requireEnabled();
		requireAnonymous();
		var verified = otp.verifyLogin(body.email(), body.challengeId(), body.code(), request.getRemoteAddr(), browserNonce(request));
		try {
			sessions.establish(request, response, verified.account().id(),verified.proof());
		} catch (RuntimeException exception) {
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
	private void requireEnabled(){if(!availability.emailEnabled()){throw new OtpFailure(503,"EMAIL_LOGIN_UNAVAILABLE");}}

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
