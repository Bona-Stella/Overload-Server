package com.overload.overload_server.auth.google;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.security.EmailOwnershipProof;
import com.overload.overload_server.security.SessionMetadata;
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
class GoogleLinkFrontendIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_link_return_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static final String SUCCESS="http://localhost:3000/account/link/complete",FAILURE="http://localhost:3000/account/link/failed";
    static final JsonMapper JSON=JsonMapper.builder().build();
    static ConfigurableApplicationContext app;
    static JdbcTemplate jdbc;
    static GoogleOidcFixtures.MockProvider provider;
    static String base;
    CookieManager cookies;
    HttpClient client;
    @BeforeAll static void start() throws Exception {
        provider=new GoogleOidcFixtures.MockProvider();GoogleOidcFixtures.provider=provider;
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class,GoogleOidcFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration","--server.port=0","--server.address=127.0.0.1",
                        "--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                        "--GOOGLE_OIDC_ENABLED=true","--GOOGLE_REAUTH_ENABLED=false","--AUTH_FRONTEND_RETURN_URI=","--AUTH_CONTACT_FRONTEND_RETURN_URI=","--AUTH_LINK_FRONTEND_RETURN_URI="+SUCCESS,
                        "--OTP_EMAIL_ENABLED=false","--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(key),"--GOOGLE_CLIENT_ID="+provider.clientId,"--GOOGLE_CLIENT_SECRET="+provider.clientSecret,
                        "--spring.session.jdbc.cleanup-cron=-","--logging.level.root=WARN","--spring.main.banner-mode=off");
        jdbc=app.getBean(JdbcTemplate.class);base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
    }
    @BeforeEach void browser() {
        EmailOtpFixtures.CLOCK.reset();provider.reset();provider.subject="link-return-"+UUID.randomUUID();app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);client=HttpClient.newBuilder().cookieHandler(cookies).build();
    }
    @AfterEach void closeBrowser(){client.close();}
    @AfterAll static void stop(){if(app!=null)app.close();if(provider!=null)provider.close();}

    @Test void csrfFormAndSignedCallbackUseFixedReturnAndPreserveOriginalAccountAndProof()throws Exception {
        login();var before=metadata();var proof=proof();var cookie=session().getId();var data=business();
        assertThat(start(false,"").statusCode()).isEqualTo(403);assertThat(get(GoogleLinkSecurity.START).statusCode()).isNotEqualTo(302);
        assertThat(start(true,"&next=https%3A%2F%2Fevil.example").statusCode()).isEqualTo(400);
        var callback=flow(start(true,""));var result=get(callback.toString());assertReturn(result,SUCCESS);
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");assertThat(result.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(metadata()).isEqualTo(before);assertThat(proof()).isEqualTo(proof);assertThat(session().getId().equals(cookie)).isTrue();assertThat(business()).isEqualTo(data);
        assertThat(JSON.readTree(get("/api/v1/auth/identities").body()).get("identities").size()).isEqualTo(2);
        assertThat(get("/api/v1/auth/session").statusCode()).isEqualTo(200);assertThat(get("/api/v1/auth/session").statusCode()).isEqualTo(200);
        assertThat(app.getBean(EmailOtpFixtures.FakeSender.class).messages).hasSize(1);assertNoTokens();
    }
    @ParameterizedTest @ValueSource(strings={"cancel","bad-signature","wrong-state","pending-expired","proof-expired","proof-replaced"})
    void callbackFailuresUseGenericReturnWithoutChangingPrincipalOrAddingIdentity(String fault)throws Exception {
        login();var before=metadata();var original=proof();var data=business();
        if(fault.equals("proof-expired"))EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(9));
        if(fault.equals("bad-signature"))provider.fault=GoogleOidcFixtures.Fault.BAD_SIGNATURE;
        var callback=flow(start(true,""));
        if(fault.equals("cancel")){var state=GoogleOidcFixtures.MockProvider.parameters(callback.getRawQuery()).get("state");callback=URI.create(base+GoogleLinkSecurity.CALLBACK+"?error=access_denied&state="+GoogleOidcFixtures.MockProvider.encode(state));}
        if(fault.equals("wrong-state"))callback=URI.create(base+GoogleLinkSecurity.CALLBACK+"?code=invalid&state=invalid");
        if(fault.equals("pending-expired"))EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(5));
        if(fault.equals("proof-expired"))EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(61));
        if(fault.equals("proof-replaced")){EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(1));setProof(new EmailOwnershipProof(original.userId(),original.sessionId(),original.contactId(),original.emailHash(),EmailOtpFixtures.CLOCK.instant(),original.source()));}
        assertReturn(get(callback.toString()),FAILURE);assertThat(metadata()).isEqualTo(before);assertThat(business()).isEqualTo(data);
        assertThat(JSON.readTree(get("/api/v1/auth/identities").body()).get("identities").size()).isEqualTo(1);assertNoTokens();
    }
    @Test void existingOtherOwnerRemainsSeparateAndConflictReturnsNoReasonOrIdentifiers()throws Exception {
        get("/api/v1/auth/csrf");get(flow(get("/oauth2/authorization/google")).toString());var other=metadata().userId();
        assertThat(post("/api/v1/auth/logout",Map.of()).statusCode()).isEqualTo(204);login();var before=metadata();var evidence=proof();var data=business();
        assertReturn(get(flow(start(true,"")).toString()),FAILURE);assertThat(metadata()).isEqualTo(before);assertThat(proof()).isEqualTo(evidence);assertThat(business()).isEqualTo(data);
        assertThat(jdbc.queryForObject("SELECT user_id FROM auth_identities WHERE provider='google' AND subject=?",UUID.class,provider.subject)).isEqualTo(other);
        assertThat(before.userId().equals(other)).isFalse();
    }
    @Test void missingProofStartReturnsFailureAndNeverStartsOAuth()throws Exception {
        login();setProof(null);int exchanges=provider.exchanges.get();assertReturn(start(true,""),FAILURE);assertThat(provider.exchanges.get()).isEqualTo(exchanges);
        assertThat(get("/api/v1/auth/session").statusCode()).isEqualTo(200);
    }
    @Test void callbackPurposeCrossingDoesNotConsumeLinkPending()throws Exception {
        login();var callback=flow(start(true,""));
        assertThat(get(callback.toString().replace("/google-link?","/google?")).statusCode()).isEqualTo(409);
        assertReturn(get(callback.toString().replace("/google-link?","/google-contact?")),GoogleContactSecurity.FAILURE);
        assertReturn(get(callback.toString()),SUCCESS);
    }
    @Test void anonymousCannotObtainSuccess()throws Exception {
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base+GoogleLinkSecurity.START)).POST(HttpRequest.BodyPublishers.ofString("")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(get(GoogleLinkSecurity.CALLBACK+"?error=access_denied").statusCode()).isEqualTo(401);
    }
    void login()throws Exception {
        String email="link-"+UUID.randomUUID()+"@example.test";
        var r=post("/api/v1/auth/email-otp/request",Map.of("email",email));assertThat(r.statusCode()).isEqualTo(202);
        var message=app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(r.body()).get("challengeId").asString()));
        assertThat(post("/api/v1/auth/email-otp/verify",Map.of("email",email,"challengeId",message.issuanceId(),"code",message.code())).statusCode()).isEqualTo(204);
    }
    HttpResponse<String> start(boolean csrf,String extra)throws Exception {
        String body=(csrf?"_csrf="+URLEncoder.encode(token(),StandardCharsets.UTF_8):"")+extra;
        return client.send(HttpRequest.newBuilder(URI.create(base+GoogleLinkSecurity.START)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String token()throws Exception{return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString();}
    HttpResponse<String> post(String path,Object body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").header("X-CSRF-TOKEN",token()).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(path.startsWith("http")?path:base+path)).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    URI flow(HttpResponse<String> response)throws Exception {
        assertThat(response.statusCode()).isEqualTo(302);var uri=URI.create(response.headers().firstValue("Location").orElseThrow());assertThat(uri.getHost()).isEqualTo("127.0.0.1");
        try(var http=HttpClient.newHttpClient()){return URI.create(http.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).headers().firstValue("Location").orElseThrow());}
    }
    Session session(){String id=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("SESSION")).findFirst().orElseThrow().getValue();return app.getBean(SessionRepository.class).findById(id);}
    SessionMetadata metadata(){return session().getAttribute(SessionMetadata.ATTRIBUTE);}
    EmailOwnershipProof proof(){return session().getAttribute(EmailOwnershipProof.ATTRIBUTE);}
    void setProof(EmailOwnershipProof proof){Session s=session();if(proof==null)s.removeAttribute(EmailOwnershipProof.ATTRIBUTE);else s.setAttribute(EmailOwnershipProof.ATTRIBUTE,proof);app.getBean(SessionRepository.class).save(s);}
    Object business(){return List.of(jdbc.queryForList("SELECT * FROM users ORDER BY id"),jdbc.queryForList("SELECT * FROM exercise_definitions ORDER BY id"),jdbc.queryForList("SELECT * FROM user_workout_settings ORDER BY user_id"),jdbc.queryForList("SELECT * FROM user_reauthentication_emails ORDER BY user_id"));}
    void assertNoTokens(){for(var raw:jdbc.queryForList("SELECT attribute_bytes FROM spring_session_attributes",byte[].class))assertThat(new String(raw,StandardCharsets.ISO_8859_1)).doesNotContain("OidcUser","OidcIdToken","OAuth2AccessToken","OAuth2AuthorizedClient");}
    static void assertReturn(HttpResponse<String> response,String target){assertThat(response.statusCode()).isEqualTo(302);assertThat(response.headers().firstValue("Location")).contains(target);}
}
