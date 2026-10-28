package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EmailReauthenticationController {
	private final AccountIdentityService identities;
	private final EmailReauthenticationService reauthentication;

	EmailReauthenticationController(AccountIdentityService identities, EmailReauthenticationService reauthentication) {
		this.identities = identities; this.reauthentication = reauthentication;
	}

	@GetMapping("/api/v1/auth/identities")
	ResponseEntity<Map<String, List<AccountIdentityService.Summary>>> identities(@AuthenticationPrincipal SessionPrincipal principal) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("identities", identities.list(principal.userId())));
	}

	@PostMapping("/api/v1/auth/reauth/email/request")
	ResponseEntity<Map<String, Object>> request(@AuthenticationPrincipal SessionPrincipal principal,
			@RequestBody Request body, HttpServletRequest request) {
		var challenge = reauthentication.request(principal.userId(), body.identityId(), request);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("status", "ACCEPTED", "challengeId", challenge));
	}

	@PostMapping("/api/v1/auth/reauth/email/verify")
	ResponseEntity<Void> verify(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody Verification body,
			HttpServletRequest request, HttpServletResponse response) {
		reauthentication.verify(principal.userId(), body.identityId(), body.challengeId(), body.code(), request, response);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	public record Request(UUID identityId) {
		@Override public String toString() { return "ReauthenticationRequest[redacted]"; }
	}
	public record Verification(UUID identityId, UUID challengeId, String code) {
		@Override public String toString() { return "ReauthenticationVerification[redacted]"; }
	}
}
