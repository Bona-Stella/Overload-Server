package com.overload.overload_server.auth.google;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "GOOGLE_OIDC_ENABLED", havingValue = "true")
public class GoogleClientConfiguration {

	public static final String LOCAL_CALLBACK = "http://localhost:8080/login/oauth2/code/google";

	@Bean(destroyMethod = "close")
	HttpClient googleTokenHttpClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	@Bean
	@ConditionalOnMissingBean(ClientRegistrationRepository.class)
	ClientRegistrationRepository googleClientRegistrations(Environment environment) {
		var id = environment.getProperty("GOOGLE_CLIENT_ID", "");
		var secret = environment.getProperty("GOOGLE_CLIENT_SECRET", "");
		if (!id.matches("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com") || secret.isBlank()
				|| !secret.equals(secret.trim()) || secret.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalStateException("Google OIDC enabled but client configuration is missing or invalid");
		}
		boolean local = environment.acceptsProfiles(Profiles.of("local"))
				&& !environment.acceptsProfiles(Profiles.of("production", "prod"));
		String callback = environment.getProperty("GOOGLE_REDIRECT_URI", local ? LOCAL_CALLBACK : "");
		if (callback.isBlank() && local) {
			callback = LOCAL_CALLBACK;
		}
		try {
			var uri = URI.create(callback);
			if (local ? !LOCAL_CALLBACK.equals(callback)
					: !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
							|| uri.getQuery() != null || uri.getFragment() != null
							|| !"/login/oauth2/code/google".equals(uri.getPath())) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Google OIDC callback must match the approved local URI or an explicit HTTPS deployment URI");
		}
		return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
				.clientId(id).clientSecret(secret).scope("openid", "email", "profile").redirectUri(callback).build());
	}

}
