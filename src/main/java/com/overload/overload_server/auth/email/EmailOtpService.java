package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.Account;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class EmailOtpService {

	private final EmailOtpRepository repository;
	private final TransactionTemplate transaction;
	private final OtpCrypto crypto;
	private final OtpEmailSender sender;
	private final AccountProvisioningService accounts;
	private final Clock clock;

	EmailOtpService(JdbcTemplate jdbc, PlatformTransactionManager manager, OtpCrypto crypto, OtpEmailSender sender,
			AccountProvisioningService accounts, Clock clock) {
		this.repository = new EmailOtpRepository(jdbc);
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.crypto = crypto;
		this.sender = sender;
		this.accounts = accounts;
		this.clock = clock;
	}

	public UUID request(String inputEmail, String remoteIp, String browserNonce) {
		requireNoAmbientTransaction();
		String email = normalize(inputEmail);
		if (!crypto.ready() || !sender.ready()) {
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		String emailKey = OtpCrypto.identifier("email", email);
		var reservation = transaction.execute(status -> {
			var now = now();
			long ipLimit = repository.rate("request-ip", remoteIp, 20, Duration.ofHours(1), now);
			long emailLimit = repository.rate("request-email", email, 5, Duration.ofHours(1), now);
			var guard = repository.lock(emailKey, now);
			long retry = Math.max(ipLimit, emailLimit);
			if (guard.failures() >= 5) {
				retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, guard.failureWindowEnd()));
			}
			if (guard.lastIssuedAt() != null && now.isBefore(guard.lastIssuedAt().plusSeconds(60))) {
				retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, guard.lastIssuedAt().plusSeconds(60)));
			}
			if (retry > 0) {
				return new Reservation(null, retry);
			}
			var id = UUID.randomUUID();
			var code = crypto.newCode();
			repository.reserve(id, emailKey, email, OtpCrypto.identifier("browser", browserNonce),
					crypto.verifier(email, id, code), now);
			return new Reservation(new OtpEmailSender.Message(id, email, code), 0);
		});
		if (reservation.retryAfter() > 0) {
			throw new OtpFailure(429, "OTP_RATE_LIMITED", reservation.retryAfter());
		}
		try {
			// No database transaction is held while making a network request.
			sender.send(reservation.message());
		} catch (RuntimeException exception) {
			transaction.executeWithoutResult(status -> repository.deliveryFailed(reservation.message().issuanceId()));
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		boolean activated = Boolean.TRUE.equals(transaction.execute(status -> {
			repository.lock(emailKey, now());
			return repository.activate(reservation.message().issuanceId(), emailKey, now());
		}));
		if (!activated) {
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		return reservation.message().issuanceId();
	}

	public Account verify(String inputEmail, UUID issuanceId, String code, String remoteIp, String browserNonce) {
		requireNoAmbientTransaction();
		String email = normalize(inputEmail);
		if (issuanceId == null || code == null || !code.matches("[0-9]{6}")) {
			throw new OtpFailure(400, "INVALID_REQUEST");
		}
		if (!crypto.ready()) {
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		String emailKey = OtpCrypto.identifier("email", email);
		var result = transaction.execute(status -> {
			var now = now();
			long ipLimit = repository.rate("verify-ip", remoteIp, 100, Duration.ofMinutes(15), now);
			long emailLimit = repository.rate("verify-email", email, 20, Duration.ofMinutes(15), now);
			var guard = repository.lock(emailKey, now);
			var challenge = repository.challenge(issuanceId, emailKey);
			// Compute and constant-time compare even when the supplied issuance does not exist.
			boolean codeMatches = MessageDigest.isEqual(crypto.verifier(email, issuanceId, code),
					challenge.map(EmailOtpRepository.Challenge::verifier).orElseGet(() -> new byte[32]));
			long retry = Math.max(ipLimit, emailLimit);
			if (guard.failures() >= 5) {
				retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, guard.failureWindowEnd()));
			}
			if (challenge.isPresent() && challenge.get().failures() >= 5 && now.isBefore(challenge.get().expiresAt())) {
				retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, challenge.get().expiresAt()));
			}
			if (retry > 0) {
				return new Verification(false, retry);
			}
			boolean valid = codeMatches && issuanceId.equals(guard.activeId()) && challenge.isPresent()
					&& "ACTIVE".equals(challenge.get().state()) && challenge.get().failures() < 5
					&& now.isBefore(challenge.get().expiresAt())
					&& challenge.get().bindingHash().equals(OtpCrypto.identifier("browser", browserNonce));
			if (!valid) {
				repository.failedAttempt(emailKey, issuanceId);
				return new Verification(false, 0);
			}
			if (!repository.claimBrowser(OtpCrypto.identifier("browser", browserNonce), issuanceId, now)) {
				return new Verification(false, 0);
			}
			repository.consume(issuanceId, now);
			return new Verification(true, 0);
		});
		if (result.retryAfter() > 0) {
			throw new OtpFailure(429, "OTP_RATE_LIMITED", result.retryAfter());
		}
		if (!result.valid()) {
			throw new OtpFailure(401, "OTP_INVALID");
		}
		// OTP consumption has committed. Provisioning owns its REQUIRES_NEW transaction.
		// On failure, never reactivate the OTP: request a fresh issuance and retry the idempotent account path.
		try {
			return accounts.provision(VerifiedIdentity.email(email), null);
		} catch (RuntimeException exception) {
			transaction.executeWithoutResult(status -> repository.releaseBrowser(issuanceId));
			throw exception;
		}
	}

	private static String normalize(String email) {
		try {
			if (email == null || email.length() > 254) {
				throw new IllegalArgumentException();
			}
			// Shape/normalization only; no identity is passed to provisioning until atomic OTP consumption succeeds.
			return VerifiedIdentity.email(email).subject();
		} catch (IllegalArgumentException exception) {
			throw new OtpFailure(400, "INVALID_REQUEST");
		}
	}

	private Instant now() {
		// Canonical server precision avoids PostgreSQL rounding an issuance across an expiry boundary.
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	private static void requireNoAmbientTransaction() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("OTP orchestration must run outside database transactions");
		}
	}

	private record Reservation(OtpEmailSender.Message message, long retryAfter) { }
	private record Verification(boolean valid, long retryAfter) { }

}
