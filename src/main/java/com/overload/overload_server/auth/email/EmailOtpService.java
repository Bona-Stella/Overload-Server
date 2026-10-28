package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.Account;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.UUID;

public class EmailOtpService {
	private final OtpChallenges challenges;
	private final AccountProvisioningService accounts;

	EmailOtpService(JdbcTemplate jdbc, PlatformTransactionManager manager, OtpCrypto crypto, OtpEmailSender sender,
			AccountProvisioningService accounts, Clock clock) {
		this.challenges = new OtpChallenges(jdbc, manager, crypto, sender, clock); this.accounts = accounts;
	}

	public UUID request(String inputEmail, String remoteIp, String browserNonce) {
		return challenges.request(login(inputEmail, browserNonce), remoteIp, () -> { });
	}

	public Account verify(String inputEmail, UUID issuanceId, String code, String remoteIp, String browserNonce) {
		var context = login(inputEmail, browserNonce);
		challenges.consume(context, issuanceId, code, remoteIp, () -> { }, now -> Boolean.TRUE);
		try { return accounts.provision(VerifiedIdentity.email(context.email()), null); }
		catch (RuntimeException exception) { challenges.releaseLogin(issuanceId); throw exception; }
	}

	OtpChallenges challenges() { return challenges; }
	private static OtpChallenges.Context login(String email, String nonce) {
		try {
			if (email == null || email.length() > 254) { throw new IllegalArgumentException(); }
			return new OtpChallenges.Context(OtpPurpose.LOGIN, VerifiedIdentity.email(email).subject(), OtpCrypto.identifier("browser", nonce), null, null);
		} catch (IllegalArgumentException exception) { throw new OtpFailure(400, "INVALID_REQUEST"); }
	}
}
