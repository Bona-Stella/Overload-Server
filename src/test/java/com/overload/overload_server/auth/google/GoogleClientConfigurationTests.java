package com.overload.overload_server.auth.google;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleClientConfigurationTests {

	@Test
	void disabledByDefaultWithoutInventedClientRegistration() {
		new WebApplicationContextRunner().withUserConfiguration(GoogleClientConfiguration.class)
				.run(context -> assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class));
	}

	@Test
	void explicitEnableWithMissingConfigurationFailsWithoutPrintingValues() {
		new WebApplicationContextRunner().withUserConfiguration(GoogleClientConfiguration.class)
				.withPropertyValues("GOOGLE_OIDC_ENABLED=true")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void localRegistrationUsesOfficialGoogleAndTheExactApprovedCallback() {
		var env = configured(); env.setActiveProfiles("local");
		var registration = new GoogleClientConfiguration().googleClientRegistrations(env).findByRegistrationId("google");
		assertThat(registration.getRedirectUri()).isEqualTo(GoogleClientConfiguration.LOCAL_CALLBACK);
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo("https://accounts.google.com");
		assertThat(registration.getProviderDetails().getAuthorizationUri()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
		assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "email", "profile");
	}

	@Test
	void localCallbackCannotBeChangedToLanIpOrAnotherPort() {
		var env = configured().withProperty("GOOGLE_REDIRECT_URI", "http://192.168.0.10:3000/login/oauth2/code/google");
		env.setActiveProfiles("local");
		assertThatThrownBy(() -> new GoogleClientConfiguration().googleClientRegistrations(env)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nonLocalAndMixedProductionProfilesRequireAnExplicitHttpsCallback() {
		var env = configured(); env.setActiveProfiles("local", "prod");
		assertThatThrownBy(() -> new GoogleClientConfiguration().googleClientRegistrations(env)).isInstanceOf(IllegalStateException.class);
		env.setProperty("GOOGLE_REDIRECT_URI", "https://overload.example.test/login/oauth2/code/google");
		assertThat(new GoogleClientConfiguration().googleClientRegistrations(env).findByRegistrationId("google").getRedirectUri())
				.isEqualTo("https://overload.example.test/login/oauth2/code/google");
		env.setProperty("GOOGLE_REDIRECT_URI", "https://overload.example.test/login/oauth2/code/google?next=external");
		assertThatThrownBy(() -> new GoogleClientConfiguration().googleClientRegistrations(env)).isInstanceOf(IllegalStateException.class);
	}

	private MockEnvironment configured() {
		return new MockEnvironment().withProperty("GOOGLE_CLIENT_ID", UUID.randomUUID() + ".apps.googleusercontent.com")
				.withProperty("GOOGLE_CLIENT_SECRET", UUID.randomUUID().toString());
	}

}
