package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.security.SessionReauthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EmailReauthenticationService {
	private final OtpChallenges challenges;
	private final AccountIdentityService identities;
	private final SessionReauthentication sessions;

	public EmailReauthenticationService(EmailOtpService login, AccountIdentityService identities, SessionReauthentication sessions) {
		this.challenges = login.challenges(); this.identities = identities; this.sessions = sessions;
	}

	public UUID request(UUID user, UUID identity, HttpServletRequest request) {
		var snapshot = sessions.capture(request, user);
		var context = context(snapshot, identity);
		return challenges.request(context, request.getRemoteAddr(), () -> validate(context, snapshot));
	}

	public void verify(UUID user, UUID identity, UUID challenge, String code, HttpServletRequest request, HttpServletResponse response) {
		var snapshot = sessions.capture(request, user);
		var context = context(snapshot, identity);
		var rotation = challenges.consume(context, challenge, code, request.getRemoteAddr(),
				() -> validate(context, snapshot), now -> {
					var updated = sessions.rotate(snapshot);
					sessions.validateRotation(updated);
					return updated;
				});
		sessions.complete(request, response, rotation);
	}

	private OtpChallenges.Context context(SessionReauthentication.Snapshot snapshot, UUID identity) {
		if (identity == null) { throw new OtpFailure(400, "INVALID_REQUEST"); }
		var email = identities.email(snapshot.userId(), identity, false).orElseThrow(() -> new OtpFailure(404, "IDENTITY_NOT_FOUND"));
		var metadata = snapshot.metadata();
		var binding = OtpCrypto.identifier("reauth-session", String.join("\n", snapshot.userId().toString(), identity.toString(),
				snapshot.sessionId(), metadata.loginStartedAt().toString(), metadata.absoluteExpiresAt().toString(), metadata.reauthenticatedAt().toString()));
		return new OtpChallenges.Context(OtpPurpose.REAUTH, email.email(), binding, snapshot.userId(), identity);
	}

	private void validate(OtpChallenges.Context context, SessionReauthentication.Snapshot snapshot) {
		var owned = identities.email(context.userId(), context.identityId(), true)
				.orElseThrow(() -> new OtpFailure(404, "IDENTITY_NOT_FOUND"));
		if (!owned.email().equals(context.email())) { throw new OtpFailure(404, "IDENTITY_NOT_FOUND"); }
		sessions.validate(snapshot);
	}
}
