package com.overload.overload_server.auth.email;

import com.overload.overload_server.security.SessionReauthentication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Credential ownership writes only. Never provisions users or sends email. */
@Service
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class EmailIdentityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ReauthenticationEmails contacts;
    private final SessionReauthentication sessions;
    private final boolean googleLoginEnabled;

    public EmailIdentityService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ReauthenticationEmails contacts, SessionReauthentication sessions,
            @org.springframework.beans.factory.annotation.Value("${GOOGLE_OIDC_ENABLED:false}") boolean googleLoginEnabled) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager); this.contacts=contacts; this.sessions=sessions;
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.googleLoginEnabled=googleLoginEnabled;
    }

    public Linked link(UUID user, HttpServletRequest request) {
        var snapshot=sessions.capture(request,user);
        return transaction.execute(status->{
            var contact=authorize(snapshot);
            var id=UUID.randomUUID();
            int inserted=jdbc.update("""
                    INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email',?)
                    ON CONFLICT ON CONSTRAINT auth_identities_credential_unique DO NOTHING
                    """,id,user,contact.email());
            var rows=jdbc.query("SELECT id,user_id FROM auth_identities WHERE provider='email' AND issuer='overload:email' AND subject=?",
                    (row,index)->new Owner(row.getObject(1,UUID.class),row.getObject(2,UUID.class)),contact.email());
            if(rows.size()!=1){throw new org.springframework.dao.DataAccessResourceFailureException("Identity write could not be confirmed");}
            var owner=rows.getFirst();
            if(!owner.user().equals(user)){throw new OtpFailure(409,"IDENTITY_ALREADY_OWNED");}
            if(inserted==1&&!owner.id().equals(id)){throw new SessionReauthentication.SessionChanged();}
            return new Linked(owner.id(),inserted==1);
        });
    }

    public void unlink(UUID user, UUID identity, HttpServletRequest request) {
        if(identity==null){throw new OtpFailure(400,"INVALID_REQUEST");}
        var snapshot=sessions.capture(request,user);
        transaction.executeWithoutResult(status->{
            authorize(snapshot);
            var owned=jdbc.query("SELECT id,provider FROM auth_identities WHERE user_id=? ORDER BY id",
                    (row,index)->new Credential(row.getObject(1,UUID.class),row.getString(2)),user);
            if(owned.stream().noneMatch(value->identity.equals(value.id())&&value.provider().equals("email"))){
                throw new OtpFailure(404,"IDENTITY_NOT_FOUND");
            }
            if(owned.stream().noneMatch(value->!value.id().equals(identity)
                    && (value.provider().equals("email") || (googleLoginEnabled&&value.provider().equals("google"))))){
                throw new OtpFailure(409,"LAST_LOGIN_IDENTITY");
            }
            // V5 history must survive credential deletion. Its old binding is already unusable since V6.
            jdbc.update("""
                    UPDATE email_otp_challenges SET retired_reauth_identity_id=reauth_identity_id,reauth_identity_id=NULL
                    WHERE purpose='REAUTH' AND reauth_user_id=? AND reauth_identity_id=? AND reauth_contact_id IS NULL
                    """,user,identity);
            if(jdbc.update("DELETE FROM auth_identities WHERE user_id=? AND id=? AND provider='email'",user,identity)!=1){
                throw new SessionReauthentication.SessionChanged();
            }
            if(!jdbc.queryForList("SELECT id FROM auth_identities WHERE user_id=? AND id=?",UUID.class,user,identity).isEmpty()){
                throw new org.springframework.dao.DataAccessResourceFailureException("Identity removal could not be confirmed");
            }
        });
    }

    private ReauthenticationEmails.Contact authorize(SessionReauthentication.Snapshot snapshot) {
        contacts.lockUser(snapshot.userId());
        // Same order for link/unlink/OTP: user -> owned identities -> session. Never locks another user's identity.
        jdbc.queryForList("SELECT id FROM auth_identities WHERE user_id=? ORDER BY id FOR UPDATE",UUID.class,snapshot.userId());
        var contact=contacts.contact(snapshot.userId()).orElseThrow(()->new OtpFailure(409,"REAUTH_EMAIL_REQUIRED"));
        Runnable proof=()->sessions.requireEmailOwnership(snapshot,contact.id(),contact.email());
        proof.run();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){proof.run();}
        });
        return contact;
    }

    public record Linked(UUID identityId,boolean created) { }
    private record Owner(UUID id,UUID user) { }
    private record Credential(UUID id,String provider) { }
}
