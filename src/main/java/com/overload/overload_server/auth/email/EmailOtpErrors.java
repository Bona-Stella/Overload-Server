package com.overload.overload_server.auth.email;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {EmailOtpController.class, EmailReauthenticationController.class,EmailIdentityController.class})
public class EmailOtpErrors {

	@ExceptionHandler(OtpFailure.class)
	ResponseEntity<Map<String, String>> failure(OtpFailure failure) {
		var builder = ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore());
		if (failure.retryAfter > 0) {
			builder.header("Retry-After", Long.toString(failure.retryAfter));
		}
		return builder.body(Map.of("code", failure.code));
	}

	@ExceptionHandler(DataAccessException.class)
	ResponseEntity<Map<String, String>> databaseUnavailable(jakarta.servlet.http.HttpServletRequest request) {
		return failure(new OtpFailure(503, request.getRequestURI().startsWith(request.getContextPath()+"/api/v1/auth/identities/email/")
				? "IDENTITY_CHANGE_UNAVAILABLE" : "OTP_UNAVAILABLE"));
	}

	@ExceptionHandler(com.overload.overload_server.security.ReauthenticationSessionRepository.EmailProofRequired.class)
	ResponseEntity<Map<String,String>> emailProofRequired(){return failure(new OtpFailure(403,"EMAIL_OWNERSHIP_PROOF_REQUIRED"));}

	@ExceptionHandler(com.overload.overload_server.security.SessionReauthentication.SessionChanged.class)
	ResponseEntity<Map<String, String>> sessionChanged() {
		return failure(new OtpFailure(401, "AUTHENTICATION_REQUIRED"));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, String>> invalidRequest() {
		return failure(new OtpFailure(400, "INVALID_REQUEST"));
	}

}
