package com.overload.overload_server.auth.google;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GoogleLoginController {

	// When enabled these exact GETs are handled by OAuth filters, not a fake controller login.
	@GetMapping({"/oauth2/authorization/google", "/login/oauth2/code/google"})
	ResponseEntity<Map<String, String>> unavailable() {
		return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
				.body(Map.of("code", "GOOGLE_LOGIN_UNAVAILABLE"));
	}

	@GetMapping("/auth/google/failure")
	ResponseEntity<Map<String, String>> failure() {
		return ResponseEntity.status(401).cacheControl(CacheControl.noStore())
				.body(Map.of("code", "GOOGLE_LOGIN_FAILED"));
	}

}
