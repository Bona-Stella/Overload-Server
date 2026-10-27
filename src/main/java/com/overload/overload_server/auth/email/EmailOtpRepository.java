package com.overload.overload_server.auth.email;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

final class EmailOtpRepository {

	private final JdbcTemplate jdbc;

	EmailOtpRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	long rate(String scope, String value, int limit, Duration window, Instant now) {
		var key = OtpCrypto.identifier(scope, value);
		jdbc.update("""
				INSERT INTO email_otp_rate_limits (bucket_key, purpose, window_started_at, hits)
				VALUES (?, 'LOGIN', ?, 0) ON CONFLICT DO NOTHING
				""", key, Timestamp.from(now));
		var state = jdbc.queryForMap("""
				SELECT window_started_at, hits FROM email_otp_rate_limits
				WHERE bucket_key = ? AND purpose = 'LOGIN' FOR UPDATE
				""", key);
		var started = ((Timestamp) state.get("window_started_at")).toInstant();
		int hits = (Integer) state.get("hits");
		if (!now.isBefore(started.plus(window))) {
			started = now;
			hits = 0;
		}
		long retry = hits >= limit ? secondsUntil(now, started.plus(window)) : 0;
		jdbc.update("""
				UPDATE email_otp_rate_limits SET window_started_at = ?, hits = ?
				WHERE bucket_key = ? AND purpose = 'LOGIN'
				""", Timestamp.from(started), Math.min(limit, hits + 1), key);
		return retry;
	}

	Guard lock(String emailKey, Instant now) {
		jdbc.update("""
				INSERT INTO email_otp_limits (email_key, purpose, failure_window_end)
				VALUES (?, 'LOGIN', ?) ON CONFLICT DO NOTHING
				""", emailKey, Timestamp.from(now.plus(Duration.ofMinutes(30))));
		var guard = jdbc.queryForObject("""
				SELECT active_challenge_id, last_issued_at, failed_attempts, failure_window_end
				FROM email_otp_limits WHERE email_key = ? AND purpose = 'LOGIN' FOR UPDATE
				""", (row, index) -> new Guard(row.getObject(1, UUID.class),
				row.getTimestamp(2) == null ? null : row.getTimestamp(2).toInstant(), row.getInt(3),
				row.getTimestamp(4).toInstant()), emailKey);
		if (!now.isBefore(guard.failureWindowEnd())) {
			jdbc.update("""
					UPDATE email_otp_limits SET failed_attempts = 0, failure_window_end = ?
					WHERE email_key = ? AND purpose = 'LOGIN'
					""", Timestamp.from(now.plus(Duration.ofMinutes(30))), emailKey);
			return new Guard(guard.activeId(), guard.lastIssuedAt(), 0, now.plus(Duration.ofMinutes(30)));
		}
		return guard;
	}

	void reserve(UUID id, String emailKey, String email, String bindingHash, byte[] verifier, Instant now) {
		jdbc.update("""
				UPDATE email_otp_challenges SET state = 'REVOKED'
				WHERE email_key = ? AND purpose = 'LOGIN' AND state IN ('PENDING', 'ACTIVE')
				""", emailKey);
		jdbc.update("""
				INSERT INTO email_otp_challenges
				(id, email_key, purpose, recipient, binding_hash, verifier, state, issued_at, expires_at)
				VALUES (?, ?, 'LOGIN', ?, ?, ?, 'PENDING', ?, ?)
				""", id, emailKey, email, bindingHash, verifier, Timestamp.from(now), Timestamp.from(now.plusSeconds(300)));
		jdbc.update("""
				UPDATE email_otp_limits SET active_challenge_id = ?, last_issued_at = ?
				WHERE email_key = ? AND purpose = 'LOGIN'
				""", id, Timestamp.from(now), emailKey);
	}

	boolean activate(UUID id, String emailKey, Instant now) {
		return jdbc.update("""
				UPDATE email_otp_challenges SET state = 'ACTIVE', accepted_at = ?
				WHERE id = ? AND email_key = ? AND purpose = 'LOGIN' AND state = 'PENDING' AND expires_at > ?
				AND id = (SELECT active_challenge_id FROM email_otp_limits WHERE email_key = ? AND purpose = 'LOGIN')
				""", Timestamp.from(now), id, emailKey, Timestamp.from(now), emailKey) == 1;
	}

	void deliveryFailed(UUID id) {
		jdbc.update("UPDATE email_otp_challenges SET state = 'FAILED' WHERE id = ? AND state = 'PENDING'", id);
	}

	Optional<Challenge> challenge(UUID id, String emailKey) {
		return jdbc.query("""
				SELECT state, expires_at, binding_hash, verifier, failed_attempts FROM email_otp_challenges
				WHERE id = ? AND email_key = ? AND purpose = 'LOGIN'
				""", (row, index) -> new Challenge(row.getString(1), row.getTimestamp(2).toInstant(),
				row.getString(3), row.getBytes(4), row.getInt(5)), id, emailKey).stream().findFirst();
	}

	void failedAttempt(String emailKey, UUID id) {
		jdbc.update("""
				UPDATE email_otp_limits SET failed_attempts = LEAST(5, failed_attempts + 1)
				WHERE email_key = ? AND purpose = 'LOGIN'
				""", emailKey);
		jdbc.update("""
				UPDATE email_otp_challenges SET failed_attempts = LEAST(5, failed_attempts + 1)
				WHERE email_key = ? AND purpose = 'LOGIN' AND id = ?
				""", emailKey, id);
	}

	void consume(UUID id, Instant now) {
		jdbc.update("""
				UPDATE email_otp_challenges SET state = 'CONSUMED', consumed_at = ?, verifier = ?
				WHERE id = ? AND state = 'ACTIVE'
				""", Timestamp.from(now), new byte[32], id);
	}

	boolean claimBrowser(String bindingHash, UUID id, Instant now) {
		return jdbc.update("""
				INSERT INTO email_otp_login_claims (binding_hash, challenge_id, claimed_at)
				VALUES (?, ?, ?) ON CONFLICT DO NOTHING
				""", bindingHash, id, Timestamp.from(now)) == 1;
	}

	void releaseBrowser(UUID id) {
		jdbc.update("DELETE FROM email_otp_login_claims WHERE challenge_id = ?", id);
	}

	static long secondsUntil(Instant now, Instant until) {
		return Math.max(1, (Duration.between(now, until).toMillis() + 999) / 1000);
	}

	record Guard(UUID activeId, Instant lastIssuedAt, int failures, Instant failureWindowEnd) { }
	record Challenge(String state, Instant expiresAt, String bindingHash, byte[] verifier, int failures) {
		@Override
		public String toString() { return "Challenge[redacted]"; }
	}

}
