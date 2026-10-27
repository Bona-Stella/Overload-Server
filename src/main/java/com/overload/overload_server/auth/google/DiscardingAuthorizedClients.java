package com.overload.overload_server.auth.google;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/** Google API/offline access is outside this login-only flow: do not retain provider tokens. */
final class DiscardingAuthorizedClients implements OAuth2AuthorizedClientRepository {

	@Override
	public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
			Authentication principal, HttpServletRequest request) {
		return null;
	}

	@Override
	public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
	}

	@Override
	public void removeAuthorizedClient(String registrationId, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
	}

}
