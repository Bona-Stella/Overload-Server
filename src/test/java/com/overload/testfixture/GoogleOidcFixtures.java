package com.overload.testfixture;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.overload.overload_server.security.SessionMetadata;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Explicit test import only: real signed tokens and HTTP code exchange, never a production login bypass. */
@TestConfiguration(proxyBeanMethods = false)
public class GoogleOidcFixtures {

	public static MockProvider provider;
	public static volatile boolean corruptMetadataRead;

	@Bean
	@Primary
	ClientRegistrationRepository mockGoogleRegistration() {
		return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
				.clientId(provider.clientId).clientSecret(provider.clientSecret)
				.authorizationUri(provider.base() + "/authorize").tokenUri(provider.base() + "/token")
				.jwkSetUri(provider.base() + "/jwks").redirectUri("{baseUrl}/login/oauth2/code/google")
				// The official Google issuer is intentionally NOT relaxed to a mock issuer.
				.scope("openid", "email", "profile").build());
	}

	@Bean
	static BeanPostProcessor metadataFaultInjector() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String name) {
				if (!(bean instanceof JdbcIndexedSessionRepository repository)) {
					return bean;
				}
				var wrapped = spy(repository);
				doAnswer(invocation -> {
					Session stored = (Session) invocation.callRealMethod();
					if (corruptMetadataRead && stored != null && stored.getAttribute("SPRING_SECURITY_CONTEXT") != null) {
						var damaged = spy(stored);
						var metadata = (SessionMetadata) stored.getAttribute(SessionMetadata.ATTRIBUTE);
						doAnswer(call -> new SessionMetadata(metadata.userId(), metadata.loginStartedAt(),
								metadata.absoluteExpiresAt().plusSeconds(1), metadata.reauthenticatedAt()))
								.when(damaged).getAttribute(SessionMetadata.ATTRIBUTE);
						return damaged;
					}
					return stored;
				}).when(wrapped).findById(anyString());
				return wrapped;
			}
		};
	}

	public enum Fault {
		NONE, BAD_SIGNATURE, UNSIGNED, HMAC_ALGORITHM, WRONG_ISSUER, WRONG_AUDIENCE, EXPIRED, MISSING_EXPIRY, FUTURE_ISSUED_AT,
		WRONG_NONCE, MISSING_NONCE, WRONG_AZP, MULTI_AUDIENCE_WITHOUT_AZP,
		MISSING_SUBJECT, BLANK_SUBJECT, MISSING_ID_TOKEN, MALFORMED_ID_TOKEN,
		TOKEN_500, TOKEN_TIMEOUT, JWK_503
	}

	public static final class MockProvider implements AutoCloseable {
		private static final JsonMapper JSON = JsonMapper.builder().build();
		private final HttpServer server;
		private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
		private final RSAKey signing;
		private final RSAKey wrongSigning;
		private final Map<String, Ticket> codes = new ConcurrentHashMap<>();
		private final List<String> secrets = new CopyOnWriteArrayList<>();
		public final String clientId = UUID.randomUUID() + ".apps.googleusercontent.com";
		public final String clientSecret = UUID.randomUUID().toString();
		public final AtomicInteger exchanges = new AtomicInteger();
		public final AtomicInteger authorizations = new AtomicInteger();
		public volatile String subject = "google-sub-one";
		public volatile String email = "same-address@example.test";
		public volatile Fault fault = Fault.NONE;
		public volatile Runnable onExchange;

		public MockProvider() throws Exception {
			signing = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
			wrongSigning = new RSAKeyGenerator(2048).keyID(signing.getKeyID()).generate();
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(executor);
			server.createContext("/authorize", this::authorize);
			server.createContext("/token", this::token);
			server.createContext("/jwks", exchange -> {
				if (fault == Fault.JWK_503) { reply(exchange, 503, Map.of("error", "unavailable")); }
				else { reply(exchange, 200, new JWKSet(signing.toPublicJWK()).toJSONObject()); }
			});
			server.start();
		}

		public String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

		public void reset() {
			codes.clear(); secrets.clear(); exchanges.set(0); authorizations.set(0);
			subject = "google-sub-one"; email = "same-address@example.test"; fault = Fault.NONE; onExchange = null;
		}

		private void authorize(HttpExchange exchange) throws IOException {
			var query = parameters(exchange.getRequestURI().getRawQuery());
			if (!"GET".equals(exchange.getRequestMethod()) || !clientId.equals(query.get("client_id"))
					|| !"code".equals(query.get("response_type")) || !"S256".equals(query.get("code_challenge_method"))
					|| query.get("nonce") == null || query.get("state") == null) {
				reply(exchange, 400, Map.of("error", "invalid_authorization")); return;
			}
			authorizations.incrementAndGet();
			String code = UUID.randomUUID().toString();
			codes.put(code, new Ticket(query.get("redirect_uri"), query.get("nonce"), query.get("code_challenge"), subject, email, fault));
			var callback = query.get("redirect_uri") + "?code=" + encode(code) + "&state=" + encode(query.get("state"));
			exchange.getResponseHeaders().set("Location", callback);
			exchange.sendResponseHeaders(302, -1); exchange.close();
		}

		private void token(HttpExchange exchange) throws IOException {
			exchanges.incrementAndGet();
			var callback = onExchange; if (callback != null) { callback.run(); }
			var form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			var ticket = codes.remove(form.get("code"));
			String expectedBasic = "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
			if (!"POST".equals(exchange.getRequestMethod()) || !expectedBasic.equals(exchange.getRequestHeaders().getFirst("Authorization"))
					|| ticket == null || !"authorization_code".equals(form.get("grant_type"))
					|| !ticket.redirect.equals(form.get("redirect_uri")) || !ticket.challenge.equals(hash(form.get("code_verifier")))) {
				reply(exchange, 400, Map.of("error", "invalid_grant")); return;
			}
			if (ticket.fault == Fault.TOKEN_500) {
				reply(exchange, 500, Map.of("error", "server_error", "error_description", "mock-provider-detail")); return;
			}
			if (ticket.fault == Fault.TOKEN_TIMEOUT) {
				try { TimeUnit.SECONDS.sleep(6); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); exchange.close(); return; }
			}
			try {
				var now = Instant.now();
				var claims = new JWTClaimsSet.Builder().issuer("https://accounts.google.com").subject(ticket.subject)
						.audience(clientId).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
						.claim("nonce", ticket.nonce).claim("email", ticket.email).claim("email_verified", true);
				switch (ticket.fault) {
					case WRONG_ISSUER -> claims.issuer("https://untrusted.example.test");
					case WRONG_AUDIENCE -> claims.audience("other-client");
					case EXPIRED -> claims.issueTime(Date.from(now.minusSeconds(1200))).expirationTime(Date.from(now.minusSeconds(600)));
					case MISSING_EXPIRY -> claims.expirationTime(null);
					case FUTURE_ISSUED_AT -> claims.issueTime(Date.from(now.plusSeconds(600))).expirationTime(Date.from(now.plusSeconds(1200)));
					case WRONG_NONCE -> claims.claim("nonce", UUID.randomUUID().toString());
					case MISSING_NONCE -> claims.claim("nonce", null);
					case WRONG_AZP -> claims.claim("azp", "other-client");
					case MULTI_AUDIENCE_WITHOUT_AZP -> claims.audience(List.of(clientId, "other-client"));
					case MISSING_SUBJECT -> claims.subject(null);
					case BLANK_SUBJECT -> claims.subject(" ");
					default -> { }
				}
				var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
						.keyID(ticket.fault == Fault.JWK_503 ? UUID.randomUUID().toString() : signing.getKeyID()).build(), claims.build());
				jwt.sign(new RSASSASigner(ticket.fault == Fault.BAD_SIGNATURE ? wrongSigning : signing));
				var hmacJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
				if (ticket.fault == Fault.HMAC_ALGORITHM) { hmacJwt.sign(new MACSigner(clientSecret)); }
				String idToken = ticket.fault == Fault.HMAC_ALGORITHM ? hmacJwt.serialize()
						: ticket.fault == Fault.UNSIGNED ? new PlainJWT(claims.build()).serialize()
						: ticket.fault == Fault.MALFORMED_ID_TOKEN ? "malformed-token" : jwt.serialize();
				String access = UUID.randomUUID().toString();
				secrets.add(access); secrets.add(idToken);
				var body = JSON.createObjectNode().put("access_token", access).put("token_type", "Bearer")
						.put("expires_in", 300).put("scope", "openid email profile");
				if (ticket.fault != Fault.MISSING_ID_TOKEN) { body.put("id_token", idToken); }
				reply(exchange, 200, body);
			} catch (Exception exception) {
				exchange.close();
			}
		}

		public boolean containsProviderToken(byte[] data) {
			var serialized = new String(data, StandardCharsets.ISO_8859_1);
			return secrets.stream().anyMatch(serialized::contains);
		}

		private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
			var bytes = JSON.writeValueAsBytes(value);
			try { exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
			finally { exchange.close(); }
		}

		public static Map<String, String> parameters(String value) {
			var result = new java.util.HashMap<String, String>();
			if (value != null) { for (var pair : value.split("&")) { var parts = pair.split("=", 2); result.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : ""); } }
			return result;
		}
		private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
		public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
		private static String hash(String value) {
			try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
			catch (Exception exception) { return "invalid"; }
		}
		@Override public void close() { server.stop(0); executor.shutdownNow(); }
		private record Ticket(String redirect, String nonce, String challenge, String subject, String email, Fault fault) {
			@Override public String toString() { return "MockOidcTicket[redacted]"; }
		}
	}

}
