package com.overload.overload_server.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthSessionController {

	@GetMapping("/api/v1/auth/session")
	ResponseEntity<Map<String, UUID>> session(@AuthenticationPrincipal SessionPrincipal principal) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("userId", principal.userId()));
	}

}
