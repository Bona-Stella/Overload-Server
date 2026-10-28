package com.overload.overload_server.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class AccountIdentityRepository {
	private final JdbcTemplate jdbc;
	AccountIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	List<AccountIdentityService.Summary> list(UUID owner) {
		return jdbc.query("SELECT id, provider FROM auth_identities WHERE user_id = ? ORDER BY provider, id",
				(row, index) -> new AccountIdentityService.Summary(row.getObject(1, UUID.class), row.getString(2)), owner);
	}

	Optional<AccountIdentityService.EmailIdentity> email(UUID owner, UUID identity, boolean lock) {
		return jdbc.query("""
				SELECT id, subject FROM auth_identities
				WHERE user_id = ? AND id = ? AND provider = 'email' AND issuer = 'overload:email'
				""" + (lock ? " FOR SHARE" : ""),
				(row, index) -> new AccountIdentityService.EmailIdentity(row.getObject(1, UUID.class), row.getString(2)),
				owner, identity).stream().findFirst();
	}

	Optional<AccountIdentityService.GoogleIdentity> google(UUID owner, UUID identity, boolean lock) {
		return jdbc.query("""
				SELECT id, issuer, subject FROM auth_identities
				WHERE user_id = ? AND id = ? AND provider = 'google' AND issuer = 'https://accounts.google.com'
				""" + (lock ? " FOR SHARE" : ""), (row, index) -> new AccountIdentityService.GoogleIdentity(
				row.getObject(1, UUID.class), row.getString(2), row.getString(3)), owner, identity).stream().findFirst();
	}
}
