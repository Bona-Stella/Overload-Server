package com.overload.overload_server.security;

import org.springframework.session.web.http.DefaultCookieSerializer;

final class AbsoluteExpiryCookieSerializer extends DefaultCookieSerializer {

	private final SessionPolicy policy;

	AbsoluteExpiryCookieSerializer(SessionPolicy policy, boolean secure) {
		this.policy = policy;
		setCookieName("SESSION");
		setCookiePath("/");
		setUseHttpOnlyCookie(true);
		setUseSecureCookie(secure);
		setSameSite("Lax");
		setUseBase64Encoding(false);
	}

	@Override
	public void writeCookieValue(CookieValue value) {
		if (!value.getCookieValue().isEmpty()) {
			var metadata = policy.activeMetadata(value.getRequest().getSession(false));
			value.setCookieMaxAge(metadata.map(policy::remainingSeconds).orElse(-1));
		}
		super.writeCookieValue(value);
	}

}
