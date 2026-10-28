package com.overload.overload_server.auth.email;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Shared challenge mechanics only. Account provisioning belongs solely to the LOGIN caller. */
final class OtpChallenges {
	private final EmailOtpRepository repository;
	private final TransactionTemplate transaction;
	private final OtpCrypto crypto;
	private final OtpEmailSender sender;
	private final Clock clock;

	OtpChallenges(JdbcTemplate jdbc, PlatformTransactionManager manager, OtpCrypto crypto, OtpEmailSender sender, Clock clock) {
		repository = new EmailOtpRepository(jdbc); transaction = new TransactionTemplate(manager);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.crypto = crypto; this.sender = sender; this.clock = clock;
	}

	UUID request(Context context, String remoteIp, Runnable validateContext) {
		return request(context,remoteIp,validateContext,now->{ });
	}
	UUID request(Context context,String remoteIp,Runnable validateContext,java.util.function.Consumer<Instant> reserved) {
		requireNoTransaction();
		if (!crypto.ready() || !sender.ready()) { throw new OtpFailure(503, "OTP_UNAVAILABLE"); }
		var reservation = transaction.execute(status -> {
			var now = now();
			long ip = repository.rate(context.purpose(), "request-ip", remoteIp, 20, Duration.ofHours(1), now);
			long email = repository.rate(context.purpose(), "request-email", context.rateRecipient(), 5, Duration.ofHours(1), now);
			var guard = repository.lock(context.purpose(), context.emailKey(), now);
			validateContext.run();
			long retry = Math.max(ip, email);
			if (guard.failures() >= 5) { retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, guard.failureWindowEnd())); }
			if (guard.lastIssuedAt() != null && now.isBefore(guard.lastIssuedAt().plusSeconds(60))) {
				retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, guard.lastIssuedAt().plusSeconds(60)));
			}
			if (retry > 0) { return new Reservation(null, retry); }
			var id = UUID.randomUUID(); var code = crypto.newCode();
			repository.reserve(id, context, crypto.verifier(context.purpose(), context.email(), id, code, context.bindingHash()), now);
			reserved.accept(now);
			return new Reservation(new OtpEmailSender.Message(id, context.email(), code, context.purpose()), 0);
		});
		if (reservation.retryAfter() > 0) { throw new OtpFailure(429, "OTP_RATE_LIMITED", reservation.retryAfter()); }
		try { sender.send(reservation.message()); }
		catch (RuntimeException exception) {
			transaction.executeWithoutResult(status -> repository.deliveryFailed(reservation.message().issuanceId(), context.purpose()));
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		boolean active = Boolean.TRUE.equals(transaction.execute(status -> {
			repository.lock(context.purpose(), context.emailKey(), now()); validateContext.run();
			return repository.activate(reservation.message().issuanceId(), context, now());
		}));
		if (!active) { throw new OtpFailure(503, "OTP_UNAVAILABLE"); }
		return reservation.message().issuanceId();
	}

	<T> T consume(Context context, UUID id, String code, String remoteIp, Runnable validateContext, Function<Instant, T> afterProof) {
		requireNoTransaction();
		if (id == null || code == null || !code.matches("[0-9]{6}")) { throw new OtpFailure(400, "INVALID_REQUEST"); }
		if (!crypto.ready()) { throw new OtpFailure(503, "OTP_UNAVAILABLE"); }
		var result = transaction.execute(status -> {
			var now = now();
			long ip = repository.rate(context.purpose(), "verify-ip", remoteIp, 100, Duration.ofMinutes(15), now);
			long email = repository.rate(context.purpose(), "verify-email", context.rateRecipient(), 20, Duration.ofMinutes(15), now);
			var guard = repository.lock(context.purpose(), context.emailKey(), now); validateContext.run();
			var challenge = repository.challenge(id, context);
			boolean matches = MessageDigest.isEqual(crypto.verifier(context.purpose(), context.email(), id, code, context.bindingHash()),
					challenge.map(EmailOtpRepository.Challenge::verifier).orElseGet(() -> new byte[32]));
			long retry = Math.max(ip, email);
			if (guard.failures() >= 5) { retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, guard.failureWindowEnd())); }
			if (challenge.isPresent() && challenge.get().failures() >= 5 && now.isBefore(challenge.get().expiresAt())) {
				retry = Math.max(retry, EmailOtpRepository.secondsUntil(now, challenge.get().expiresAt()));
			}
			if (retry > 0) { return new Outcome<T>(null, retry, false); }
			boolean valid = matches && id.equals(guard.activeId()) && challenge.isPresent()
					&& "ACTIVE".equals(challenge.get().state()) && challenge.get().failures() < 5
					&& now.isBefore(challenge.get().expiresAt()) && challenge.get().bindingHash().equals(context.bindingHash())
					&& Objects.equals(challenge.get().userId(), context.userId()) && Objects.equals(challenge.get().identityId(), context.identityId())
					&& Objects.equals(challenge.get().contactId(), context.contactId());
			if (!valid) { repository.failedAttempt(context, id); return new Outcome<T>(null, 0, false); }
			if (context.purpose() == OtpPurpose.LOGIN && !repository.claimBrowser(context.bindingHash(), id, now)) {
				return new Outcome<T>(null, 0, false);
			}
			repository.consume(id, context.purpose(), now);
			return new Outcome<>(afterProof.apply(now), 0, true);
		});
		if (result.retryAfter() > 0) { throw new OtpFailure(429, "OTP_RATE_LIMITED", result.retryAfter()); }
		if (!result.valid()) { throw new OtpFailure(401, "OTP_INVALID"); }
		return result.value();
	}

	void releaseLogin(UUID id) { transaction.executeWithoutResult(status -> repository.releaseBrowser(id)); }
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
	private static void requireNoTransaction() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("OTP orchestration must run outside database transactions"); }
	}
	record Context(OtpPurpose purpose, String email, String bindingHash, UUID userId, UUID identityId, UUID contactId) {
		Context(OtpPurpose purpose, String email, String bindingHash, UUID userId, UUID identityId) { this(purpose,email,bindingHash,userId,identityId,null); }
		String rateRecipient() { return purpose == OtpPurpose.LOGIN ? email : userId + "\n" + email; }
		String emailKey() { return OtpCrypto.identifier("email", rateRecipient()); }
		@Override public String toString() { return "OtpContext[redacted]"; }
	}
	private record Reservation(OtpEmailSender.Message message, long retryAfter) { }
	private record Outcome<T>(T value, long retryAfter, boolean valid) { }
}
