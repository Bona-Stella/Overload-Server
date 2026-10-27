package com.overload.overload_server.security;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

	@GetMapping("/api/v1/auth/csrf")
	public ResponseEntity<Token> csrf(CsrfToken csrf) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new Token(csrf.getHeaderName(), csrf.getToken()));
	}

	public record Token(String headerName, String token) {
	}

}
