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
		if (!value.getCookieValue().isEmpty() && value.getRequest().getAttribute(SessionReauthentication.COOKIE)
				instanceof SessionReauthentication.ReauthenticationCookie marker) {
			// The outer SessionRepositoryFilter may still commit the old request snapshot. Never restore its cookie.
			var rotation = marker.rotation();
			try {
				marker.verify();
				var rotated = new CookieValue(value.getRequest(), value.getResponse(), rotation.sessionId());
				rotated.setCookieMaxAge(policy.remainingSeconds(rotation.metadata()));
				super.writeCookieValue(rotated);
			} catch (RuntimeException failure) {
				marker.owner().discard(rotation);
				value.getResponse().setStatus(failure instanceof SessionReauthentication.SessionChanged ? 401 : 503);
				var expired = new CookieValue(value.getRequest(), value.getResponse(), "");
				expired.setCookieMaxAge(0);
				super.writeCookieValue(expired);
				// Propagate during the controller's explicit write. The outer filter's final commit must retain the error status.
				if (value.getCookieValue().equals(rotation.sessionId())) { throw failure; }
			}
			return;
		}
		if (!value.getCookieValue().isEmpty()) {
			var metadata = policy.activeMetadata(value.getRequest().getSession(false));
			value.setCookieMaxAge(metadata.map(policy::remainingSeconds).orElse(-1));
		}
		super.writeCookieValue(value);
	}

}
