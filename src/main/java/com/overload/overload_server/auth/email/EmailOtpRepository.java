package com.overload.overload_server.auth.email;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

final class EmailOtpRepository {
	private final JdbcTemplate jdbc;
	EmailOtpRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	long rate(OtpPurpose purpose, String scope, String value, int limit, Duration window, Instant now) {
		var key = OtpCrypto.identifier(scope, value);
		jdbc.update("""
				INSERT INTO email_otp_rate_limits (bucket_key, purpose, window_started_at, hits)
				VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING
				""", key, purpose.name(), Timestamp.from(now));
		var state = jdbc.queryForMap("""
				SELECT window_started_at, hits FROM email_otp_rate_limits
				WHERE bucket_key = ? AND purpose = ? FOR UPDATE
				""", key, purpose.name());
		var started = ((Timestamp) state.get("window_started_at")).toInstant();
		int hits = (Integer) state.get("hits");
		if (!now.isBefore(started.plus(window))) { started = now; hits = 0; }
		long retry = hits >= limit ? secondsUntil(now, started.plus(window)) : 0;
		jdbc.update("""
				UPDATE email_otp_rate_limits SET window_started_at = ?, hits = ? WHERE bucket_key = ? AND purpose = ?
				""", Timestamp.from(started), Math.min(limit, hits + 1), key, purpose.name());
		return retry;
	}

	Guard lock(OtpPurpose purpose, String emailKey, Instant now) {
		jdbc.update("""
				INSERT INTO email_otp_limits (email_key, purpose, failure_window_end)
				VALUES (?, ?, ?) ON CONFLICT DO NOTHING
				""", emailKey, purpose.name(), Timestamp.from(now.plus(Duration.ofMinutes(30))));
		var guard = jdbc.queryForObject("""
				SELECT active_challenge_id, last_issued_at, failed_attempts, failure_window_end
				FROM email_otp_limits WHERE email_key = ? AND purpose = ? FOR UPDATE
				""", (row, index) -> new Guard(row.getObject(1, UUID.class),
				row.getTimestamp(2) == null ? null : row.getTimestamp(2).toInstant(), row.getInt(3), row.getTimestamp(4).toInstant()),
				emailKey, purpose.name());
		if (!now.isBefore(guard.failureWindowEnd())) {
			jdbc.update("""
					UPDATE email_otp_limits SET failed_attempts = 0, failure_window_end = ? WHERE email_key = ? AND purpose = ?
					""", Timestamp.from(now.plus(Duration.ofMinutes(30))), emailKey, purpose.name());
			return new Guard(guard.activeId(), guard.lastIssuedAt(), 0, now.plus(Duration.ofMinutes(30)));
		}
		return guard;
	}

	void reserve(UUID id, OtpChallenges.Context context, byte[] verifier, Instant now) {
		jdbc.update("""
				UPDATE email_otp_challenges SET state = 'REVOKED'
				WHERE email_key = ? AND purpose = ? AND state IN ('PENDING', 'ACTIVE')
				""", context.emailKey(), context.purpose().name());
		jdbc.update("""
				INSERT INTO email_otp_challenges
				(id, email_key, purpose, recipient, binding_hash, verifier, state, issued_at, expires_at, reauth_user_id, reauth_identity_id)
				VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
				""", id, context.emailKey(), context.purpose().name(), context.email(), context.bindingHash(), verifier,
				Timestamp.from(now), Timestamp.from(now.plusSeconds(300)), context.userId(), context.identityId());
		jdbc.update("""
				UPDATE email_otp_limits SET active_challenge_id = ?, last_issued_at = ? WHERE email_key = ? AND purpose = ?
				""", id, Timestamp.from(now), context.emailKey(), context.purpose().name());
	}

	boolean activate(UUID id, OtpChallenges.Context context, Instant now) {
		return jdbc.update("""
				UPDATE email_otp_challenges SET state = 'ACTIVE', accepted_at = ?
				WHERE id = ? AND email_key = ? AND purpose = ? AND state = 'PENDING' AND expires_at > ?
				AND id = (SELECT active_challenge_id FROM email_otp_limits WHERE email_key = ? AND purpose = ?)
				""", Timestamp.from(now), id, context.emailKey(), context.purpose().name(), Timestamp.from(now),
				context.emailKey(), context.purpose().name()) == 1;
	}

	void deliveryFailed(UUID id, OtpPurpose purpose) {
		jdbc.update("UPDATE email_otp_challenges SET state = 'FAILED' WHERE id = ? AND purpose = ? AND state = 'PENDING'", id, purpose.name());
	}

	Optional<Challenge> challenge(UUID id, OtpChallenges.Context context) {
		return jdbc.query("""
				SELECT state, expires_at, binding_hash, verifier, failed_attempts, reauth_user_id, reauth_identity_id
				FROM email_otp_challenges WHERE id = ? AND email_key = ? AND purpose = ?
				""", (row, index) -> new Challenge(row.getString(1), row.getTimestamp(2).toInstant(), row.getString(3),
				row.getBytes(4), row.getInt(5), row.getObject(6, UUID.class), row.getObject(7, UUID.class)),
				id, context.emailKey(), context.purpose().name()).stream().findFirst();
	}

	void failedAttempt(OtpChallenges.Context context, UUID id) {
		jdbc.update("""
				UPDATE email_otp_limits SET failed_attempts = LEAST(5, failed_attempts + 1) WHERE email_key = ? AND purpose = ?
				""", context.emailKey(), context.purpose().name());
		jdbc.update("""
				UPDATE email_otp_challenges SET failed_attempts = LEAST(5, failed_attempts + 1) WHERE email_key = ? AND purpose = ? AND id = ?
				""", context.emailKey(), context.purpose().name(), id);
	}

	void consume(UUID id, OtpPurpose purpose, Instant now) {
		if (jdbc.update("""
				UPDATE email_otp_challenges SET state = 'CONSUMED', consumed_at = ?, verifier = ?
				WHERE id = ? AND purpose = ? AND state = 'ACTIVE'
				""", Timestamp.from(now), new byte[32], id, purpose.name()) != 1) { throw new OtpFailure(401, "OTP_INVALID"); }
	}

	boolean claimBrowser(String bindingHash, UUID id, Instant now) {
		return jdbc.update("""
				INSERT INTO email_otp_login_claims (binding_hash, challenge_id, claimed_at)
				SELECT ?, id, ? FROM email_otp_challenges WHERE id = ? AND purpose = 'LOGIN'
				ON CONFLICT DO NOTHING
				""", bindingHash, Timestamp.from(now), id) == 1;
	}
	void releaseBrowser(UUID id) { jdbc.update("DELETE FROM email_otp_login_claims WHERE challenge_id = ?", id); }
	static long secondsUntil(Instant now, Instant until) { return Math.max(1, (Duration.between(now, until).toMillis() + 999) / 1000); }
	record Guard(UUID activeId, Instant lastIssuedAt, int failures, Instant failureWindowEnd) { }
	record Challenge(String state, Instant expiresAt, String bindingHash, byte[] verifier, int failures, UUID userId, UUID identityId) {
		@Override public String toString() { return "Challenge[redacted]"; }
	}
}
