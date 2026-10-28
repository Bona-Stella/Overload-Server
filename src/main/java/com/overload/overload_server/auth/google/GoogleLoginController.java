package com.overload.overload_server.auth.google;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;

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

	@PostMapping("/oauth2/reauth/google")
	ResponseEntity<Map<String, String>> reauthenticationUnavailable() {
		return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
				.body(Map.of("code", "GOOGLE_REAUTH_UNAVAILABLE"));
	}

	@GetMapping("/login/oauth2/code/google-reauth")
	ResponseEntity<Map<String, String>> callbackUnavailable() { return reauthenticationUnavailable(); }

	@GetMapping("/auth/google/reauth/failure")
	ResponseEntity<Map<String, String>> reauthenticationFailure() {
		return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
				.body(Map.of("code", "GOOGLE_REAUTH_FAILED"));
	}
	@PostMapping("/oauth2/contact/google")
	ResponseEntity<Map<String,String>> contactUnavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(Map.of("code","GOOGLE_CONTACT_UNAVAILABLE"));}
	@GetMapping("/login/oauth2/code/google-contact")
	ResponseEntity<Map<String,String>> contactCallbackUnavailable(){return contactUnavailable();}
	@GetMapping("/auth/google/contact/failure")
	ResponseEntity<Map<String,String>> contactFailure(){return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(Map.of("code","GOOGLE_CONTACT_FAILED"));}
	@PostMapping("/oauth2/link/google")
	ResponseEntity<Map<String,String>> linkUnavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(Map.of("code","GOOGLE_LINK_UNAVAILABLE"));}
	@GetMapping("/login/oauth2/code/google-link")
	ResponseEntity<Map<String,String>> linkCallbackUnavailable(){return linkUnavailable();}
	@GetMapping("/auth/google/link/failure")
	ResponseEntity<Map<String,String>> linkFailure(){return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(Map.of("code","GOOGLE_LINK_FAILED"));}

}
