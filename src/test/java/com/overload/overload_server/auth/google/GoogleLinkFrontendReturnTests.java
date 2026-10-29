package com.overload.overload_server.auth.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class GoogleLinkFrontendReturnTests {
    static final String SUCCESS="http://localhost:3000/account/link/complete";
    @Test void emptyPreservesJsonAndFixedFailureAndOtherPurposes() {
        var target=new GoogleFrontendReturn(new MockEnvironment());
        assertThat(target.linkSuccess()).isEqualTo("/api/v1/auth/identities");
        assertThat(target.linkFailure()).isEqualTo("/auth/google/link/failure");
        assertThat(target.linkConfigured()).isFalse();
        assertThat(target.contactSuccess()).isEqualTo("/api/v1/auth/reauth/email/contact");
        assertThat(target.success()).isEqualTo("/api/v1/auth/session");
    }
    @Test void linkOptInDoesNotChangeLoginOrContact() {
        var env=new MockEnvironment().withProperty("AUTH_LINK_FRONTEND_RETURN_URI",SUCCESS);env.setActiveProfiles("local");
        var target=new GoogleFrontendReturn(env);
        assertThat(target.linkSuccess()).isEqualTo(SUCCESS);assertThat(target.linkFailure()).isEqualTo("http://localhost:3000/account/link/failed");
        assertThat(target.linkConfigured()).isTrue();assertThat(target.success()).isEqualTo("/api/v1/auth/session");
        assertThat(target.contactFailure()).isEqualTo("/auth/google/contact/failure");
    }
    @ParameterizedTest @ValueSource(strings={"/account/link/complete","//evil.example/account/link/complete","http://127.0.0.1:3000/account/link/complete","http://localhost:3001/account/link/complete","http://localhost:3000/account/contact/complete","http://localhost:3000/account/link/complete?next=external","http://localhost:3000/account/link/complete#token","http://user@localhost:3000/account/link/complete","http://localhost:3000/account/link/%63omplete","http://localhost:3000/x/../account/link/complete","http://localhost:3000/account/link/complete\r\nX-Injected:yes"})
    void rejectsLocalRedirectAndInjection(String value) {
        var env=new MockEnvironment().withProperty("AUTH_LINK_FRONTEND_RETURN_URI",value);env.setActiveProfiles("local");
        assertThatThrownBy(()->new GoogleFrontendReturn(env)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(value);
    }
    @Test void productionAndMixedProfilesRequireFixedHttps() {
        var env=new MockEnvironment().withProperty("AUTH_LINK_FRONTEND_RETURN_URI",SUCCESS);env.setActiveProfiles("local","prod");
        assertThatThrownBy(()->new GoogleFrontendReturn(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("AUTH_LINK_FRONTEND_RETURN_URI","https://frontend.example.test/account/link/complete");
        assertThat(new GoogleFrontendReturn(env).linkFailure()).isEqualTo("https://frontend.example.test/account/link/failed");
    }
    @ParameterizedTest @ValueSource(strings={"http://frontend.example.test/account/link/complete","https://localhost/account/link/complete","https://frontend.example.test:8443/account/link/complete","https://user@frontend.example.test/account/link/complete","https://frontend.example.test/account/link/complete?state=secret","https://frontend.example.test/account/link/complete#token","https://frontend.example.test/other"})
    void productionRejectsNonFixedDestinations(String value) {
        assertThatThrownBy(()->new GoogleFrontendReturn(new MockEnvironment().withProperty("AUTH_LINK_FRONTEND_RETURN_URI",value))).isInstanceOf(IllegalStateException.class);
    }
}
