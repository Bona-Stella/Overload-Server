package com.overload.overload_server.auth.google;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.security.EmailOwnershipProof;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.GoogleOidcFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres") @Testcontainers
class GoogleContactFrontendIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_contact_return_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static final String SUCCESS = "http://localhost:3000/account/contact/complete";
    static final String FAILURE = "http://localhost:3000/account/contact/failed";
    static final String CONTACT = "/api/v1/auth/reauth/email/contact";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static ConfigurableApplicationContext app;
    static JdbcTemplate jdbc;
    static GoogleOidcFixtures.MockProvider provider;
    static String base;
    CookieManager cookies;
    HttpClient client;

    @BeforeAll static void start() throws Exception {
        provider = new GoogleOidcFixtures.MockProvider(); GoogleOidcFixtures.provider = provider;
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, GoogleOidcFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration", "--server.port=0", "--server.address=127.0.0.1",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--GOOGLE_OIDC_ENABLED=true", "--GOOGLE_REAUTH_ENABLED=false", "--AUTH_FRONTEND_RETURN_URI=", "--AUTH_LINK_FRONTEND_RETURN_URI=", "--AUTH_CONTACT_FRONTEND_RETURN_URI=" + SUCCESS,
                        "--OTP_EMAIL_ENABLED=false", "--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key),
                        "--GOOGLE_CLIENT_ID=" + provider.clientId, "--GOOGLE_CLIENT_SECRET=" + provider.clientSecret,
                        "--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
        jdbc = app.getBean(JdbcTemplate.class);
        com.overload.testfixture.MixedPersistenceAssertions.verify(app);
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }
    @BeforeEach void freshBrowser() {
        EmailOtpFixtures.CLOCK.reset(); provider.reset(); provider.subject = "contact-return-" + UUID.randomUUID();
        app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL); client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }
    @AfterEach void closeBrowser() { client.close(); }
    @AfterAll static void stop() { if (app != null) app.close(); if (provider != null) provider.close(); }

    @Test void formPostAndSignedCallbackReturnWithoutSecretsAndPreserveOriginalSession() throws Exception {
        login(); var before = session(); SessionMetadata metadata = before.getAttribute(SessionMetadata.ATTRIBUTE);
        assertThat(get(GoogleContactSecurity.START).statusCode()).isNotEqualTo(302);
        assertThat(start(false, "").statusCode()).isEqualTo(403);
        assertThat(start(true, "&next=https%3A%2F%2Fevil.example").statusCode()).isEqualTo(400);
        URI callback = providerCallback(start(true, ""));
        var result = get(callback.toString()); assertReturn(result, SUCCESS);
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(result.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        var contact = JSON.readTree(get(CONTACT).body());
        assertThat(contact.get("status").asString()).isEqualTo("REQUIRED"); assertThat(contact.has("candidate")).isTrue();
        assertThat(get(CONTACT).body()).doesNotContain(provider.email, provider.subject);
        assertThat((SessionMetadata)session().getAttribute(SessionMetadata.ATTRIBUTE)).isEqualTo(metadata);
        assertThat((Object)session().getAttribute(EmailOwnershipProof.ATTRIBUTE)).isNull();
        assertThat(session().getId().equals(before.getId())).isTrue();
        assertThat(app.getBean(EmailOtpFixtures.FakeSender.class).messages).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_reauthentication_emails WHERE user_id=?", Long.class, metadata.userId())).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "wrong-sub", "unverified-email", "bad-signature", "expired", "wrong-state"})
    void rejectedContactReturnsToFixedFailureWithoutReplacingAccount(String fault) throws Exception {
        login(); SessionMetadata before = session().getAttribute(SessionMetadata.ATTRIBUTE);
        if (fault.equals("wrong-sub")) provider.subject = "different-existing-subject";
        if (fault.equals("unverified-email")) provider.emailVerified = false;
        if (fault.equals("bad-signature")) provider.fault = GoogleOidcFixtures.Fault.BAD_SIGNATURE;
        URI callback = providerCallback(start(true, ""));
        if (fault.equals("expired")) EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(5));
        if (fault.equals("cancel")) {
            String state = GoogleOidcFixtures.MockProvider.parameters(callback.getRawQuery()).get("state");
            callback = URI.create(base + GoogleContactSecurity.CALLBACK + "?error=access_denied&state=" + GoogleOidcFixtures.MockProvider.encode(state));
        }
        if (fault.equals("wrong-state")) callback = URI.create(base + GoogleContactSecurity.CALLBACK + "?code=invalid&state=invalid");
        assertReturn(get(callback.toString()), FAILURE);
        assertThat((SessionMetadata)session().getAttribute(SessionMetadata.ATTRIBUTE)).isEqualTo(before);
        assertThat(JSON.readTree(get(CONTACT).body()).has("candidate")).isFalse();
        assertThat(app.getBean(EmailOtpFixtures.FakeSender.class).messages).isEmpty();
    }

    @Test void anonymousStartCannotObtainAFrontendSuccess() throws Exception {
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + GoogleContactSecurity.START)).POST(HttpRequest.BodyPublishers.ofString("")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(get(CONTACT).statusCode()).isEqualTo(401);
    }
    private void login() throws Exception {
        get("/api/v1/auth/csrf"); var result = get(providerCallback(get("/oauth2/authorization/google")).toString());
        assertReturn(result, "/api/v1/auth/session"); assertThat(get("/api/v1/auth/session").statusCode()).isEqualTo(200);
    }
    private HttpResponse<String> start(boolean csrf, String extra) throws Exception {
        SessionMetadata m = session().getAttribute(SessionMetadata.ATTRIBUTE);
        UUID identity = jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='google'", UUID.class, m.userId());
        String token = JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString();
        String body = "identityId=" + identity + (csrf ? "&_csrf=" + URLEncoder.encode(token, StandardCharsets.UTF_8) : "") + extra;
        return client.send(HttpRequest.newBuilder(URI.create(base + GoogleContactSecurity.START)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI providerCallback(HttpResponse<String> result) throws Exception {
        assertThat(result.statusCode()).isEqualTo(302); URI uri = URI.create(result.headers().firstValue("Location").orElseThrow());
        assertThat(uri.getHost()).isEqualTo("127.0.0.1");
        try (var http = HttpClient.newHttpClient()) { return URI.create(http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).headers().firstValue("Location").orElseThrow()); }
    }
    private HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private Session session() {
        String id = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
        return app.getBean(SessionRepository.class).findById(id);
    }
    private static void assertReturn(HttpResponse<String> result, String location) {
        assertThat(result.statusCode()).isEqualTo(302); assertThat(result.headers().firstValue("Location")).contains(location);
    }
}
