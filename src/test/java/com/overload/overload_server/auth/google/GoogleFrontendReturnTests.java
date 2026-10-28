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
}
