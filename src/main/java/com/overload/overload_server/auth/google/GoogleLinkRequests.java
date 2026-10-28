package com.overload.overload_server.auth.google;

import com.overload.overload_server.account.*;
import com.overload.overload_server.auth.identity.GoogleIdentityService;
import com.overload.overload_server.security.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="GOOGLE_OIDC_ENABLED",havingValue="true")
final class GoogleLinkRequests extends GoogleBoundRequests {
    private final GoogleIdentityService changes;
    GoogleLinkRequests(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountIdentityService identities,SessionReauthentication sessions,SessionPolicy policy,GoogleIdentityService changes){
        super("GOOGLE_LINK",jdbc,manager,identities,sessions,policy);this.changes=changes;
    }
    @Override void begin(HttpServletRequest request,SessionReauthentication.Snapshot snapshot,java.util.UUID ignored){
        transaction.executeWithoutResult(status->{var verified=changes.authorizeLink(snapshot,null,null);startPending(request,snapshot,verified.proof(),changes.version(snapshot.userId()));});
    }
    @Override protected void validatePending(Pending pending){
        if(pending.emailProof()==null){throw new ReauthenticationSessionRepository.EmailProofRequired();}
        changes.authorizeLink(pending.snapshot(),pending.emailProof(),pending.identityVersion());
        Runnable validity=()->GoogleAuthenticationTime.validateRequest(pending.startedAt(),policy.now());validity.run();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){validity.run();}
        });
    }
    @Override void verified(HttpServletRequest request,HttpServletResponse response,OidcUser user){
        if(!(request.getAttribute(claimed) instanceof Pending pending)||pending.emailProof()==null){throw new SessionReauthentication.SessionChanged();}
        var identity=VerifiedIdentity.google(user.getIdToken().getIssuer().toString(),user.getIdToken().getSubject());
        changes.link(pending.snapshot(),pending.emailProof(),pending.identityVersion(),identity,()->GoogleAuthenticationTime.validateRequest(pending.startedAt(),policy.now()));
    }
}
