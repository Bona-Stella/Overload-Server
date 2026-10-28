package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.Account;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.security.EmailOwnershipProof;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.UUID;

public class EmailOtpService {
	private final OtpChallenges challenges;
	private final AccountProvisioningService accounts;
	private final ReauthenticationEmails contacts;

	EmailOtpService(JdbcTemplate jdbc, PlatformTransactionManager manager, OtpCrypto crypto, OtpEmailSender sender,
			AccountProvisioningService accounts, Clock clock) {
		this.challenges = new OtpChallenges(jdbc, manager, crypto, sender, clock); this.accounts = accounts;
		this.contacts = new ReauthenticationEmails(jdbc, manager, clock);
	}

	public UUID request(String inputEmail, String remoteIp, String browserNonce) {
		return challenges.request(login(inputEmail, browserNonce), remoteIp, () -> { });
	}

	public Account verify(String inputEmail, UUID issuanceId, String code, String remoteIp, String browserNonce) {
		return verifyLogin(inputEmail,issuanceId,code,remoteIp,browserNonce).account();
	}

	VerifiedLogin verifyLogin(String inputEmail, UUID issuanceId, String code, String remoteIp, String browserNonce) {
		var context = login(inputEmail, browserNonce);
		var provedAt=challenges.consume(context, issuanceId, code, remoteIp, () -> { }, now -> now);
		try {
			var account=accounts.provision(VerifiedIdentity.email(context.email()), null);
			var contact=contacts.verifiedEmailLogin(account.id(),context.email(),provedAt);
			var proof=contact.email().equals(context.email())
					? EmailOwnershipProof.verified(contact.id(),contact.email(),provedAt,EmailOwnershipProof.Source.EMAIL_LOGIN) : null;
			return new VerifiedLogin(account,proof);
		}
		catch (RuntimeException exception) { challenges.releaseLogin(issuanceId); throw exception; }
	}
	record VerifiedLogin(Account account,EmailOwnershipProof.Verification proof) {
		@Override public String toString(){return "VerifiedEmailLogin[redacted]";}
	}

	OtpChallenges challenges() { return challenges; }
	public boolean available(){return challenges.ready();}
	private static OtpChallenges.Context login(String email, String nonce) {
		try {
			if (email == null || email.length() > 254) { throw new IllegalArgumentException(); }
			return new OtpChallenges.Context(OtpPurpose.LOGIN, VerifiedIdentity.email(email).subject(), OtpCrypto.identifier("browser", nonce), null, null);
		} catch (IllegalArgumentException exception) { throw new OtpFailure(400, "INVALID_REQUEST"); }
	}
}
