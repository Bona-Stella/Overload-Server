package com.overload.overload_server.auth.identity;

import com.overload.overload_server.auth.google.GooglePendingInvalidation;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.security.EmailOwnershipProof;
import com.overload.overload_server.security.SessionPolicy;
import com.overload.overload_server.security.SessionReauthentication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.sql.Timestamp;
import java.util.UUID;

@Service @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class GoogleIdentityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdentityChangeAuthorization authorization;
    private final SessionReauthentication sessions;
    private final LoginAvailability availability;
    private final SessionPolicy policy;
    private final GooglePendingInvalidation pending;
    public GoogleIdentityService(JdbcTemplate jdbc,PlatformTransactionManager manager,IdentityChangeAuthorization authorization,
            SessionReauthentication sessions,LoginAvailability availability,SessionPolicy policy,GooglePendingInvalidation pending){
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);this.authorization=authorization;this.sessions=sessions;
        this.availability=availability;this.policy=policy;this.pending=pending;
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public long version(UUID user){return jdbc.queryForList("SELECT version FROM google_identity_change_versions WHERE user_id=?",Long.class,user).stream().findFirst().orElse(0L);}
    public IdentityChangeAuthorization.Authorized authorizeLink(SessionReauthentication.Snapshot snapshot,EmailOwnershipProof proof,Long expectedVersion){
        if(!availability.usable("google")){throw new IdentityChangeFailure(503,"GOOGLE_LINK_UNAVAILABLE");}
        var verified=authorization.authorize(snapshot,proof);
        if(expectedVersion!=null){
            Runnable check=()->{if(version(snapshot.userId())!=expectedVersion){throw new IdentityChangeFailure(409,"IDENTITY_CHANGE_SUPERSEDED");}};
            check.run();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){check.run();}});
        }
        return verified;
    }
    public void link(SessionReauthentication.Snapshot snapshot,EmailOwnershipProof proof,long version,VerifiedIdentity google,Runnable requestValid){
        transaction.executeWithoutResult(status->{
            authorizeLink(snapshot,proof,version);requestValid.run();
            jdbc.update("""
                    INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'google',?,?)
                    ON CONFLICT ON CONSTRAINT auth_identities_credential_unique DO NOTHING
                    """,UUID.randomUUID(),snapshot.userId(),google.issuer(),google.subject());
            var owners=jdbc.queryForList("SELECT user_id FROM auth_identities WHERE provider='google' AND issuer=? AND subject=?",UUID.class,google.issuer(),google.subject());
            if(owners.size()!=1){throw new org.springframework.dao.DataAccessResourceFailureException("Identity write could not be confirmed");}
            if(!owners.getFirst().equals(snapshot.userId())){throw new IdentityChangeFailure(409,"IDENTITY_ALREADY_OWNED");}
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){requestValid.run();}});
        });
    }
    public void unlink(UUID user,UUID identity,HttpServletRequest request){
        if(identity==null){throw new IdentityChangeFailure(400,"INVALID_REQUEST");}
        var snapshot=sessions.capture(request,user);
        transaction.executeWithoutResult(status->{
            authorization.authorize(snapshot,null);
            long previousVersion=version(user);
            var owned=jdbc.query("SELECT id,provider FROM auth_identities WHERE user_id=? ORDER BY id",(row,index)->new Credential(row.getObject(1,UUID.class),row.getString(2)),user);
            if(owned.stream().noneMatch(c->c.id().equals(identity)&&c.provider().equals("google"))){throw new IdentityChangeFailure(404,"IDENTITY_NOT_FOUND");}
            if(owned.stream().noneMatch(c->!c.id().equals(identity)&&availability.usable(c.provider()))){throw new IdentityChangeFailure(409,"LAST_LOGIN_IDENTITY");}
            var now=Timestamp.from(policy.now());
            jdbc.update("""
                    UPDATE reauth_email_candidates SET retired_google_identity_id=google_identity_id,google_identity_id=NULL,
                    invalidated_at=?,expires_at=LEAST(expires_at,?) WHERE user_id=? AND google_identity_id=?
                    """,now,now,user,identity);
            jdbc.update("""
                    UPDATE email_otp_challenges SET retired_reauth_identity_id=reauth_identity_id,reauth_identity_id=NULL
                    WHERE purpose='CONTACT_ENROLL' AND reauth_user_id=? AND reauth_identity_id=?
                    """,user,identity);
            pending.retire(user,identity);
            if(jdbc.update("DELETE FROM auth_identities WHERE user_id=? AND id=? AND provider='google'",user,identity)!=1){throw new SessionReauthentication.SessionChanged();}
            if(!jdbc.queryForList("SELECT id FROM auth_identities WHERE user_id=? AND id=?",UUID.class,user,identity).isEmpty()){throw new org.springframework.dao.DataAccessResourceFailureException("Identity removal could not be confirmed");}
            jdbc.update("INSERT INTO google_identity_change_versions(user_id,version) VALUES (?,1) ON CONFLICT(user_id) DO UPDATE SET version=google_identity_change_versions.version+1",user);
            if(version(user)!=previousVersion+1){throw new org.springframework.dao.DataAccessResourceFailureException("Identity version could not be persisted");}
        });
    }
    private record Credential(UUID id,String provider){ }
}
