package com.overload.overload_server.account;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owner must come from the authenticated server principal; this service never links or provisions identities. */
@Service
public class AccountIdentityService {
	private final AccountIdentityRepository repository;
	AccountIdentityService(AccountIdentityRepository repository) { this.repository = repository; }

	public List<Summary> list(UUID owner) { return repository.list(owner); }
	public Optional<EmailIdentity> email(UUID owner, UUID identity, boolean lock) { return repository.email(owner, identity, lock); }
	public Optional<GoogleIdentity> google(UUID owner, UUID identity, boolean lock) { return repository.google(owner, identity, lock); }

	public record GoogleIdentity(UUID id, String issuer, String subject) {
		@Override public String toString() { return "GoogleIdentity[redacted]"; }
	}

	public record Summary(UUID id, String provider) {
		@Override public String toString() { return "IdentitySummary[redacted]"; }
	}
	public record EmailIdentity(UUID id, String email) {
		@Override public String toString() { return "EmailIdentity[redacted]"; }
	}
}
