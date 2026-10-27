package com.overload.testfixture;

import com.overload.overload_server.auth.email.OtpEmailSender;
import com.overload.overload_server.security.SessionPrincipal;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@TestConfiguration(proxyBeanMethods = false)
public class EmailOtpFixtures {

	public static final SecurityFixtures.MutableClock CLOCK = new SecurityFixtures.MutableClock();

	@Bean
	@Primary
	Clock otpTestClock() { return CLOCK; }

	@Bean
	@Primary
	FakeSender fakeSender() { return new FakeSender(); }

	public static final class FakeSender implements OtpEmailSender {
		public final Map<UUID, Message> messages = new ConcurrentHashMap<>();
		public volatile String mode = "ACCEPT";
		public volatile boolean available = true;
		public volatile Consumer<Message> action;

		public void reset() {
			messages.clear(); mode = "ACCEPT"; available = true; action = null;
		}

		@Override public boolean ready() { return available; }

		@Override public void send(Message message) {
			var callback = action;
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				throw new AssertionError("Network sender must be outside a database transaction");
			}
			messages.put(message.issuanceId(), message);
			if (callback != null) { callback.accept(message); }
			if ("CRASH".equals(mode)) { throw new SimulatedCrash(); }
			if (!"ACCEPT".equals(mode)) { throw new IllegalStateException("Simulated mail failure"); }
		}
	}

	public static final class SimulatedCrash extends Error {
		public SimulatedCrash() { super("Simulated interruption after mail intent"); }
	}

	@RestController
	public static class OtpProbe {
		private final JdbcTemplate jdbc;
		public OtpProbe(JdbcTemplate jdbc) { this.jdbc = jdbc; }

		@GetMapping("/__otp_test__/me")
		Map<String, Object> me(@AuthenticationPrincipal SessionPrincipal principal) {
			return jdbc.queryForMap("SELECT id, data_generation FROM users WHERE id = ?", principal.userId());
		}

		@PostMapping("/__otp_test__/mutate")
		ResponseEntity<Void> mutate() { return ResponseEntity.noContent().build(); }
	}

}
