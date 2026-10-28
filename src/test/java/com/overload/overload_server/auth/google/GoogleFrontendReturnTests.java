package com.overload.overload_server.auth.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class GoogleFrontendReturnTests {
    @Test void defaultPreservesExistingLiveContract() {
        var target = new GoogleFrontendReturn(new MockEnvironment());
        assertThat(target.success()).isEqualTo("/api/v1/auth/session");
        assertThat(target.failure()).isEqualTo("/auth/google/failure");
        assertThat(target.contactSuccess()).isEqualTo("/api/v1/auth/reauth/email/contact");
        assertThat(target.contactFailure()).isEqualTo("/auth/google/contact/failure");
    }
    @Test void localUsesOnlyFixedFrontendRoutes() {
        var env = new MockEnvironment().withProperty("AUTH_FRONTEND_RETURN_URI", GoogleFrontendReturn.LOCAL);
        env.setActiveProfiles("local");
        var target = new GoogleFrontendReturn(env);
        assertThat(target.success()).isEqualTo(GoogleFrontendReturn.LOCAL);
        assertThat(target.failure()).isEqualTo("http://localhost:3000/auth/failed");
    }
    @ParameterizedTest @ValueSource(strings = {"https://frontend.example.test/auth/complete", "http://127.0.0.1:3000/auth/complete", "http://localhost:3001/auth/complete", "//evil.example/auth/complete", "/auth/complete", "http://localhost:3000/auth/complete?next=https://evil.example", "http://localhost:3000/auth/complete#token", "http://evil@localhost:3000/auth/complete", "http://localhost:3000/auth/%63omplete", "http://localhost:3000/x/../auth/complete", " http://localhost:3000/auth/complete", "http://localhost:3000/auth/complete\r\nX-Injected:yes"})
    void localRejectsOtherDestinationsAndInjectionWithoutReflectingValues(String value) {
        var env = new MockEnvironment().withProperty("AUTH_FRONTEND_RETURN_URI", value); env.setActiveProfiles("local");
        assertThatThrownBy(() -> new GoogleFrontendReturn(env)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(value);
    }
    @Test void productionAndMixedProfilesRequireTrustedExplicitHttps() {
        var env = new MockEnvironment().withProperty("AUTH_FRONTEND_RETURN_URI", GoogleFrontendReturn.LOCAL); env.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> new GoogleFrontendReturn(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("AUTH_FRONTEND_RETURN_URI", "https://frontend.example.test/auth/complete");
        assertThat(new GoogleFrontendReturn(env).failure()).isEqualTo("https://frontend.example.test/auth/failed");
    }
    @ParameterizedTest @ValueSource(strings = {"http://frontend.example.test/auth/complete", "https://localhost/auth/complete", "https://frontend.example.test:8443/auth/complete", "https://user@frontend.example.test/auth/complete", "https://frontend.example.test/auth/complete?next=external", "https://frontend.example.test/auth/complete#token", "https://frontend.example.test/elsewhere"})
    void productionRejectsUnsafeOrNonFixedConfiguration(String value) {
        var env = new MockEnvironment().withProperty("AUTH_FRONTEND_RETURN_URI", value);
        assertThatThrownBy(() -> new GoogleFrontendReturn(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void contactIsIndependentAndUsesItsOwnFixedRoutes() {
        var env = new MockEnvironment().withProperty("AUTH_CONTACT_FRONTEND_RETURN_URI", "http://localhost:3000/account/contact/complete");
        env.setActiveProfiles("local");
        var target = new GoogleFrontendReturn(env);
        assertThat(target.success()).isEqualTo("/api/v1/auth/session");
        assertThat(target.contactSuccess()).isEqualTo("http://localhost:3000/account/contact/complete");
        assertThat(target.contactFailure()).isEqualTo("http://localhost:3000/account/contact/failed");
        env.setProperty("AUTH_CONTACT_FRONTEND_RETURN_URI", ""); env.setProperty("AUTH_FRONTEND_RETURN_URI", GoogleFrontendReturn.LOCAL);
        assertThat(new GoogleFrontendReturn(env).contactSuccess()).isEqualTo("/api/v1/auth/reauth/email/contact");
    }
    @ParameterizedTest @ValueSource(strings = {"/account/contact/complete", "//evil.example/account/contact/complete", "http://localhost:3001/account/contact/complete", "http://127.0.0.1:3000/account/contact/complete", "http://localhost:3000/auth/complete", "http://localhost:3000/account/contact/complete?next=external", "http://localhost:3000/account/contact/complete#token", "http://user@localhost:3000/account/contact/complete", "http://localhost:3000/account/contact/%63omplete", "http://localhost:3000/account/../account/contact/complete", "http://localhost:3000/account/contact/complete\r\nX-Injected:yes"})
    void contactRejectsNonFixedLocalDestinations(String value) {
        var env = new MockEnvironment().withProperty("AUTH_CONTACT_FRONTEND_RETURN_URI", value); env.setActiveProfiles("local");
        assertThatThrownBy(() -> new GoogleFrontendReturn(env)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(value);
    }
    @Test void contactProductionAndMixedProfilesRequireExplicitHttps() {
        var env = new MockEnvironment().withProperty("AUTH_CONTACT_FRONTEND_RETURN_URI", "http://localhost:3000/account/contact/complete");
        env.setActiveProfiles("local", "production");
        assertThatThrownBy(() -> new GoogleFrontendReturn(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("AUTH_CONTACT_FRONTEND_RETURN_URI", "https://frontend.example.test/account/contact/complete");
        assertThat(new GoogleFrontendReturn(env).contactFailure()).isEqualTo("https://frontend.example.test/account/contact/failed");
    }
}
