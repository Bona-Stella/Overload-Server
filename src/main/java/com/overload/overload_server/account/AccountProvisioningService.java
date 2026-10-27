package com.overload.overload_server.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Internal boundary: call only after authentication has verified the supplied identity. */
@Service
public class AccountProvisioningService {

	private final AccountRepository repository;
	private final TransactionTemplate transaction;

	AccountProvisioningService(AccountRepository repository, PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	public Account provision(VerifiedIdentity identity, String nickname) {
		Objects.requireNonNull(identity, "identity");
		var account = transaction.execute(status -> {
			var existing = repository.findByIdentity(identity);
			if (existing.isPresent()) {
				return existing.get();
			}
			var created = repository.createUser(nickname);
			if (!repository.claimIdentity(created, identity)) {
				// A concurrent winner committed this credential. Roll back the losing user as well.
				status.setRollbackOnly();
				return null;
			}
			repository.insertInitialWorkoutData(created);
			return created;
		});
		if (account != null) {
			return account;
		}
		// Read the winner in a fresh transaction, after the losing transaction has fully rolled back.
		return transaction.execute(status -> repository.findByIdentity(identity)
				.orElseThrow(() -> new IllegalStateException("Concurrent account is no longer available")));
	}

}
