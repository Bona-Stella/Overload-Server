package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.security.SessionReauthentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A contact is not a login identity. Address equality never grants account ownership. */
public class ReauthenticationEmails {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final Clock clock;
	public ReauthenticationEmails(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
		this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager); this.clock=clock;
	}
	public void lockUser(UUID user) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Contact mutation requires a transaction"); }
		if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class,user).isEmpty()) {
			throw new SessionReauthentication.SessionChanged();
		}
	}
	public Optional<Contact> contact(UUID user) {
		return jdbc.query("SELECT id,email,verified_at FROM user_reauthentication_emails WHERE user_id=? AND status='VERIFIED'",
				(row,index)->new Contact(row.getObject(1,UUID.class),row.getString(2),row.getTimestamp(3).toInstant()),user).stream().findFirst();
	}
	void verifiedEmailLogin(UUID user, String email, Instant provedAt) {
		transaction.executeWithoutResult(status->{
			lockUser(user);
			var owned=jdbc.queryForList("SELECT id FROM auth_identities WHERE user_id=? AND provider='email' AND issuer='overload:email' AND subject=? FOR SHARE",UUID.class,user,email);
			if(owned.isEmpty()){throw new SessionReauthentication.SessionChanged();}
			insert(user,email,provedAt,"EMAIL_LOGIN_OTP");
			if(contact(user).isEmpty()){throw new org.springframework.dao.DataAccessResourceFailureException("Verified contact could not be persisted");}
		});
	}
	private void insert(UUID user,String email,Instant at,String source) {
		// Only first registration. A later login or Google email change cannot replace an existing contact.
		jdbc.update("""
				INSERT INTO user_reauthentication_emails(user_id,id,email,verified_at,verification_source)
				VALUES (?,?,?,?,?) ON CONFLICT (user_id) DO NOTHING
				""",user,UUID.randomUUID(),email,Timestamp.from(at),source);
	}
	public void googleCandidate(SessionReauthentication.Snapshot snapshot, AccountIdentityService.GoogleIdentity identity, String verifiedEmail) {
		lockUser(snapshot.userId());
		if(contact(snapshot.userId()).isPresent()){throw new OtpFailure(409,"REAUTH_EMAIL_ALREADY_VERIFIED");}
		var id=UUID.randomUUID(); var now=clock.instant();
		jdbc.update("UPDATE reauth_email_candidates SET expires_at=LEAST(expires_at,?) WHERE user_id=? AND consumed_at IS NULL",Timestamp.from(now),snapshot.userId());
		jdbc.update("INSERT INTO reauth_email_candidates(id,user_id,google_identity_id,google_subject,email,binding_hash,expires_at) VALUES (?,?,?,?,?,?,?)",
				id,snapshot.userId(),identity.id(),identity.subject(),verifiedEmail,binding(snapshot,id),Timestamp.from(now.plusSeconds(300)));
	}
	Candidate candidate(SessionReauthentication.Snapshot snapshot,UUID id) {
		if(id==null){throw new OtpFailure(400,"INVALID_REQUEST");}
		var rows=jdbc.query("SELECT id,google_identity_id,email,binding_hash,expires_at,consumed_at,google_subject FROM reauth_email_candidates WHERE user_id=? AND id=?",
				(row,index)->new Candidate(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),row.getString(4),row.getTimestamp(5).toInstant(),row.getTimestamp(6)==null,row.getString(7)),snapshot.userId(),id);
		var value=rows.stream().findFirst().orElseThrow(()->new OtpFailure(401,"OTP_INVALID"));
		if(!value.pending()||!clock.instant().isBefore(value.expiresAt())||!binding(snapshot,id).equals(value.binding())){throw new OtpFailure(401,"OTP_INVALID");}
		return value;
	}
	void registerCandidate(SessionReauthentication.Snapshot snapshot, Candidate candidate, Instant at) {
		lockUser(snapshot.userId());
		if(contact(snapshot.userId()).isPresent()){throw new OtpFailure(409,"REAUTH_EMAIL_ALREADY_VERIFIED");}
		candidate(snapshot,candidate.id());
		insert(snapshot.userId(),candidate.email(),at,"GOOGLE_EMAIL_OTP");
		var saved=contact(snapshot.userId()).orElseThrow(()->new org.springframework.dao.DataAccessResourceFailureException("Verified contact could not be persisted"));
		if(!saved.email().equals(candidate.email())){throw new SessionReauthentication.SessionChanged();}
		if(jdbc.update("UPDATE reauth_email_candidates SET consumed_at=? WHERE user_id=? AND id=? AND consumed_at IS NULL",
				Timestamp.from(at),snapshot.userId(),candidate.id())!=1){throw new SessionReauthentication.SessionChanged();}
	}
	void reservedCandidate(SessionReauthentication.Snapshot snapshot,UUID candidate,Instant issuedAt){
		if(jdbc.update("UPDATE reauth_email_candidates SET expires_at=GREATEST(expires_at,?) WHERE user_id=? AND id=? AND consumed_at IS NULL",
				Timestamp.from(issuedAt.plusSeconds(300)),snapshot.userId(),candidate)!=1){throw new SessionReauthentication.SessionChanged();}
	}
	public Map<String,Object> status(SessionReauthentication.Snapshot snapshot) {
		var contact=contact(snapshot.userId());
		if(contact.isPresent()){return Map.of("status","VERIFIED","maskedEmail",mask(contact.get().email()));}
		var candidates=jdbc.queryForList("SELECT id FROM reauth_email_candidates WHERE user_id=? AND consumed_at IS NULL AND expires_at>? ORDER BY expires_at DESC",UUID.class,snapshot.userId(),Timestamp.from(clock.instant()));
		for(var id:candidates){try{var candidate=candidate(snapshot,id);return Map.of("status","REQUIRED","candidate",Map.of("id",id,"maskedEmail",mask(candidate.email())));}catch(OtpFailure ignored){/* Other session's candidate is never disclosed. */}}
		return Map.of("status","REQUIRED");
	}
	public void cancel(UUID user) { transaction.executeWithoutResult(status->{lockUser(user);jdbc.update("UPDATE reauth_email_candidates SET expires_at=LEAST(expires_at,?) WHERE user_id=? AND consumed_at IS NULL",Timestamp.from(clock.instant()),user);}); }
	static String binding(SessionReauthentication.Snapshot snapshot,UUID reference) {
		var m=snapshot.metadata();
		return OtpCrypto.identifier("reauth-session",String.join("\n",snapshot.userId().toString(),reference.toString(),snapshot.sessionId(),m.loginStartedAt().toString(),m.absoluteExpiresAt().toString(),m.reauthenticatedAt().toString()));
	}
	private static String mask(String email){return email.substring(0,1)+"***@***";}
	public record Contact(UUID id,String email,Instant verifiedAt){@Override public String toString(){return "ReauthenticationEmail[redacted]";}}
	record Candidate(UUID id,UUID identity,String email,String binding,Instant expiresAt,boolean pending,String subject){@Override public String toString(){return "ReauthenticationEmailCandidate[redacted]";}}
}
