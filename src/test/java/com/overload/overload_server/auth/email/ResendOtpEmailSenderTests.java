package com.overload.overload_server.auth.email;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendOtpEmailSenderTests {

	@Test
	void sendsStablePayloadAndIdempotencyKeyOnlyToTheLocalProtocolStub() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var calls = new AtomicInteger(); var firstBody = new AtomicReference<String>();
		var firstKey = new AtomicReference<String>(); var stable = new AtomicReference<>(true);
		server.createContext("/emails", exchange -> {
			var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			var key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
			if (calls.incrementAndGet() == 1) { firstBody.set(body); firstKey.set(key); }
			else { stable.set(firstBody.get().equals(body) && firstKey.get().equals(key)); }
			var response = "{\"id\":\"local-test-message\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
		});
		server.start();
		try (var client = HttpClient.newHttpClient()) {
			var sender = new ResendOtpEmailSender(true, UUID.randomUUID().toString(), "sender@example.invalid", client,
					endpoint(server), Duration.ofSeconds(1));
			var message = new OtpEmailSender.Message(UUID.randomUUID(), "recipient@example.invalid",
					new OtpCrypto(OtpCryptoTests.randomKey()).newCode());
			sender.send(message); sender.send(message);
			assertThat(calls.get()).isEqualTo(2); assertThat(stable.get()).isTrue();
			assertThat(firstKey.get()).isEqualTo("email-otp/" + message.issuanceId());
			assertThat(firstBody.get().contains(message.code()) && firstBody.get().contains(message.recipient())).isTrue();
		} finally { server.stop(0); }
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 422, 429, 500, 200})
	void providerErrorsOrAmbiguousSuccessDoNotBecomeAcceptedMail(int status) throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/emails", exchange -> {
			exchange.getRequestBody().readAllBytes();
			var body = "{\"error\":\"do not propagate provider body\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
		});
		server.start();
		try (var client = HttpClient.newHttpClient()) {
			var sender = new ResendOtpEmailSender(true, UUID.randomUUID().toString(), "sender@example.invalid", client,
					endpoint(server), Duration.ofSeconds(1));
			assertThatThrownBy(() -> sender.send(message())).isInstanceOf(OtpFailure.class).hasMessage("OTP_UNAVAILABLE");
		} finally { server.stop(0); }
	}

	@Test
	void timeoutIsUncertainAndDisabledSenderNeverMakesANetworkRequest() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var calls = new AtomicInteger();
		server.createContext("/emails", exchange -> {
			calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
			try { Thread.sleep(150); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			try { exchange.sendResponseHeaders(200, -1); } finally { exchange.close(); }
		});
		server.start();
		try (var client = HttpClient.newHttpClient()) {
			var disabled = new ResendOtpEmailSender(false, "", "", client, endpoint(server), Duration.ofSeconds(1));
			assertThat(disabled.ready()).isFalse();
			assertThatThrownBy(() -> disabled.send(message())).isInstanceOf(OtpFailure.class);
			assertThat(calls.get()).isZero();
			var sender = new ResendOtpEmailSender(true, UUID.randomUUID().toString(), "sender@example.invalid", client,
					endpoint(server), Duration.ofMillis(40));
			assertThatThrownBy(() -> sender.send(message())).isInstanceOf(OtpFailure.class).hasMessage("OTP_UNAVAILABLE");
		} finally { server.stop(0); }
	}

	private static OtpEmailSender.Message message() {
		return new OtpEmailSender.Message(UUID.randomUUID(), "recipient@example.invalid",
				new OtpCrypto(OtpCryptoTests.randomKey()).newCode());
	}
	private static URI endpoint(HttpServer server) {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/emails");
	}

}
