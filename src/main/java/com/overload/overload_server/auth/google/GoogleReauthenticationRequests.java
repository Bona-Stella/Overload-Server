package com.overload.overload_server.auth.google;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.security.SessionPolicy;
import com.overload.overload_server.security.SessionReauthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;

/** Strict auth_time flow retained, independently opt-in. */
@Component
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name={"GOOGLE_OIDC_ENABLED","GOOGLE_REAUTH_ENABLED"},havingValue="true")
final class GoogleReauthenticationRequests extends GoogleBoundRequests {
	GoogleReauthenticationRequests(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountIdentityService identities,SessionReauthentication sessions,SessionPolicy policy){
		super("GOOGLE_REAUTH",jdbc,manager,identities,sessions,policy);
	}

	@Override void verified(HttpServletRequest request, HttpServletResponse response, OidcUser user) {
		if (!(request.getAttribute(claimed) instanceof Pending pending)) { throw new SessionReauthentication.SessionChanged(); }
		var authTime = user.getIdToken().getAuthenticatedAt();
		Runnable proof = () -> GoogleAuthenticationTime.validate(pending.startedAt(), authTime, policy.now());
		proof.run();
		var rotation = transaction.execute(status -> {
			var identity = validateOwner(pending);
			if (!identity.issuer().equals(user.getIdToken().getIssuer().toString()) || !identity.subject().equals(user.getIdToken().getSubject())) {
				throw new SessionReauthentication.SessionChanged();
			}
			proof.run();
			// Do not mint a fresh ten-minute window from token/callback time. Bound future clock skew and retain any newer prior proof.
			var evidence = authTime.isAfter(policy.now()) ? policy.now() : authTime;
			if (evidence.isBefore(pending.snapshot().metadata().reauthenticatedAt())) { evidence = pending.snapshot().metadata().reauthenticatedAt(); }
			var updated = sessions.rotate(pending.snapshot(), evidence);
			proof.run(); sessions.validateRotation(updated);
			return updated;
		});
		sessions.complete(request, response, rotation, proof);
	}

}
