package com.overload.overload_server.auth.email;

/** Server-selected security purpose; never accepted from a request DTO. */
public enum OtpPurpose {
	LOGIN, REAUTH, CONTACT_ENROLL
}
