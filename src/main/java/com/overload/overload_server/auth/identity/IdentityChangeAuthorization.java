package com.overload.overload_server.auth.identity;

import com.overload.overload_server.auth.email.ReauthenticationEmails;
import com.overload.overload_server.security.EmailOwnershipProof;
import com.overload.overload_server.security.SessionReauthentication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** Shared user -> owned identities -> source session lock and exact email-evidence boundary. */
@Service @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class IdentityChangeAuthorization {
    private final JdbcTemplate jdbc;
    private final ReauthenticationEmails contacts;
    private final SessionReauthentication sessions;
    public IdentityChangeAuthorization(JdbcTemplate jdbc,ReauthenticationEmails contacts,SessionReauthentication sessions){this.jdbc=jdbc;this.contacts=contacts;this.sessions=sessions;}
    public Authorized authorize(SessionReauthentication.Snapshot snapshot,EmailOwnershipProof expected){
        contacts.lockUser(snapshot.userId());
        jdbc.queryForList("SELECT id FROM auth_identities WHERE user_id=? ORDER BY id FOR UPDATE",UUID.class,snapshot.userId());
        var contact=contacts.contact(snapshot.userId()).orElseThrow(()->new IdentityChangeFailure(409,"REAUTH_EMAIL_REQUIRED"));
        var proof=sessions.requireEmailOwnership(snapshot,contact.id(),contact.email());
        if(expected!=null&&!proof.equals(expected)){throw new com.overload.overload_server.security.ReauthenticationSessionRepository.EmailProofRequired();}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                if(!proof.equals(sessions.requireEmailOwnership(snapshot,contact.id(),contact.email()))){throw new com.overload.overload_server.security.ReauthenticationSessionRepository.EmailProofRequired();}
            }
        });
        return new Authorized(contact,proof);
    }
    public record Authorized(ReauthenticationEmails.Contact contact,EmailOwnershipProof proof){
        @Override public String toString(){return "IdentityChangeAuthorization[redacted]";}
    }
}
