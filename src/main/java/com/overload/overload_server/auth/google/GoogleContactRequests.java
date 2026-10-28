package com.overload.overload_server.auth.google;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.auth.email.ReauthenticationEmails;
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

@Component
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="GOOGLE_OIDC_ENABLED",havingValue="true")
final class GoogleContactRequests extends GoogleBoundRequests {
	private final ReauthenticationEmails contacts;
	GoogleContactRequests(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountIdentityService identities,SessionReauthentication sessions,SessionPolicy policy,ReauthenticationEmails contacts){
		super("GOOGLE_CONTACT",jdbc,manager,identities,sessions,policy);this.contacts=contacts;
	}
	@Override void begin(HttpServletRequest request,SessionReauthentication.Snapshot snapshot,java.util.UUID identity){
		if(contacts.contact(snapshot.userId()).isPresent()){throw new SessionReauthentication.SessionChanged();}
		super.begin(request,snapshot,identity);
	}
	@Override void verified(HttpServletRequest request,HttpServletResponse response,OidcUser user){
		if(!(request.getAttribute(claimed) instanceof Pending pending)){throw new SessionReauthentication.SessionChanged();}
		if(!Boolean.TRUE.equals(user.getIdToken().getEmailVerified())){throw new SessionReauthentication.SessionChanged();}
		String raw=user.getIdToken().getEmail();
		if(raw==null||raw.length()>254){throw new SessionReauthentication.SessionChanged();}
		String email=VerifiedIdentity.email(raw).subject();
		transaction.executeWithoutResult(status->{
			contacts.lockUser(pending.snapshot().userId());
			var identity=validateOwner(pending);
			if(!identity.issuer().equals(user.getIdToken().getIssuer().toString())||!identity.subject().equals(user.getIdToken().getSubject())){throw new SessionReauthentication.SessionChanged();}
			sessions.validate(pending.snapshot());
			GoogleAuthenticationTime.validateRequest(pending.startedAt(),policy.now());
			contacts.googleCandidate(pending.snapshot(),identity,email);
			sessions.validate(pending.snapshot());
		});
	}
}
