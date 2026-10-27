package com.overload.overload_server.auth.email;

final class OtpFailure extends RuntimeException {

	final int status;
	final String code;
	final long retryAfter;

	OtpFailure(int status, String code) {
		this(status, code, 0);
	}

	OtpFailure(int status, String code, long retryAfter) {
		super(code);
		this.status = status;
		this.code = code;
		this.retryAfter = retryAfter;
	}

}
