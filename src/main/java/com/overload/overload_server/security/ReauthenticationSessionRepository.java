package com.overload.overload_server.security;

import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** Uses the OTP transaction's connection. SessionRepository uses REQUIRES_NEW and cannot provide this lock. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ReauthenticationSessionRepository {
	private static final String CONTEXT = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
	private final JdbcTemplate jdbc;
	private final SessionPolicy policy;

	public ReauthenticationSessionRepository(JdbcTemplate jdbc, SessionPolicy policy) {
		this.jdbc = jdbc;
		this.policy = policy;
	}

	public void validate(SessionReauthentication.Snapshot expected) {
		locked(expected);
	}

	public void validateRotation(SessionReauthentication.Rotation rotation) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Reauthentication requires the OTP transaction");
		}
		if (!matches(read(rotation.sessionId(), true), rotation.original().userId(), rotation.metadata())) {
			throw new SessionReauthentication.SessionChanged();
		}
		if(!java.util.Objects.equals(rotation.emailProof(),read(rotation.sessionId(),false).proof())){throw new SessionReauthentication.SessionChanged();}
	}

	public EmailOwnershipProof requireEmailOwnership(SessionReauthentication.Snapshot expected,UUID contact,String email) {
		var stored=locked(expected);
		if(!(stored.proof() instanceof EmailOwnershipProof proof)
				|| !proof.matches(expected.userId(),expected.sessionId(),contact,email,policy.now())){
			throw new EmailProofRequired();
		}
		return proof;
	}

	public SessionReauthentication.Rotation withEmailProof(SessionReauthentication.Rotation rotation,EmailOwnershipProof.Verification verification) {
		validateRotation(rotation);
		var proof=verification.bind(rotation.original().userId(),rotation.sessionId());
		if(!validContact(proof,rotation.sessionId())){throw new EmailProofRequired();}
		var row=read(rotation.sessionId(),true);
		jdbc.update("""
				INSERT INTO spring_session_attributes VALUES (?,?,?)
				ON CONFLICT (session_primary_id,attribute_name) DO UPDATE SET attribute_bytes=EXCLUDED.attribute_bytes
				""",row.primary(),EmailOwnershipProof.ATTRIBUTE,new SerializingConverter().convert(proof));
		var result=new SessionReauthentication.Rotation(rotation.original(),rotation.sessionId(),rotation.metadata(),proof);
		validateRotation(result);
		return result;
	}

	private boolean validContact(EmailOwnershipProof proof,String session) {
		return jdbc.query("SELECT id,email FROM user_reauthentication_emails WHERE user_id=? AND status='VERIFIED'",
				(row,index)->proof.matches(proof.userId(),session,row.getObject(1,UUID.class),row.getString(2),policy.now()),proof.userId()).stream().anyMatch(Boolean.TRUE::equals);
	}

	public SessionReauthentication.Rotation rotate(SessionReauthentication.Snapshot expected) {
		return rotate(expected, policy.now());
	}

	public SessionReauthentication.Rotation rotate(SessionReauthentication.Snapshot expected, java.time.Instant authenticatedAt) {
		var stored = locked(expected);
		var metadata = new SessionMetadata(expected.userId(), expected.metadata().loginStartedAt(),
				expected.metadata().absoluteExpiresAt(), authenticatedAt);
		if (!policy.isActive(metadata)) { throw new SessionReauthentication.SessionChanged(); }
		var primary = UUID.randomUUID().toString();
		var id = UUID.randomUUID().toString();
		long accessed = System.currentTimeMillis();
		int remaining = Math.max(1, policy.remainingSeconds(metadata));
		jdbc.update("""
				INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,
				max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, ?, ?, ?, ?, ?)
				""", primary, id, stored.created(), accessed, remaining, accessed + remaining * 1000L, expected.userId().toString());
		var serializer = new SerializingConverter();
		jdbc.update("INSERT INTO spring_session_attributes VALUES (?, ?, ?)", primary, CONTEXT, serializer.convert(stored.context()));
		jdbc.update("INSERT INTO spring_session_attributes VALUES (?, ?, ?)", primary, SessionMetadata.ATTRIBUTE, serializer.convert(metadata));
		EmailOwnershipProof carried=null;
		if(stored.proof() instanceof EmailOwnershipProof proof && proof.userId()!=null && expected.userId().equals(proof.userId())
				&& validContact(proof,expected.sessionId())){
			carried=proof.rebind(id);
			jdbc.update("INSERT INTO spring_session_attributes VALUES (?,?,?)",primary,EmailOwnershipProof.ATTRIBUTE,serializer.convert(carried));
		}
		// Recheck before deleting the source and immediately after the writes. Exceptions roll back OTP and session together.
		locked(expected);
		jdbc.update("DELETE FROM spring_session WHERE primary_id = ?", stored.primary());
		if (!policy.isActive(metadata)) { throw new SessionReauthentication.SessionChanged(); }
		var saved = read(id, false);
		if (!matches(saved, expected.userId(), metadata)) { throw new SessionReauthentication.SessionChanged(); }
		if(!java.util.Objects.equals(carried,saved.proof())){throw new SessionReauthentication.SessionChanged();}
		return new SessionReauthentication.Rotation(expected, id, metadata,carried);
	}

	private Stored locked(SessionReauthentication.Snapshot expected) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Reauthentication requires the OTP transaction");
		}
		var stored = read(expected.sessionId(), true);
		if (!matches(stored, expected.userId(), expected.metadata())) { throw new SessionReauthentication.SessionChanged(); }
		return stored;
	}

	private boolean matches(Stored stored, UUID user, SessionMetadata metadata) {
		return stored != null && stored.expires() > System.currentTimeMillis() && policy.isActive(metadata)
				&& metadata.equals(stored.metadata()) && stored.context() instanceof SecurityContext context
				&& context.getAuthentication() != null && context.getAuthentication().isAuthenticated()
				&& context.getAuthentication().getPrincipal() instanceof SessionPrincipal principal && user.equals(principal.userId());
	}

	private Stored read(String id, boolean lock) {
		var rows = jdbc.query("SELECT primary_id, creation_time, expiry_time FROM spring_session WHERE session_id = ?"
				+ (lock ? " FOR UPDATE" : ""), (row, index) -> new Stored(row.getString(1), row.getLong(2), row.getLong(3), null, null,null), id);
		if (rows.isEmpty()) { return null; }
		var row = rows.getFirst();
		var attributes = jdbc.query("SELECT attribute_name, attribute_bytes FROM spring_session_attributes WHERE session_primary_id = ?",
				(rs, index) -> new Attribute(rs.getString(1), new DeserializingConverter().convert(rs.getBytes(2))), row.primary());
		Object context = null, metadata = null, proof=null;
		for (var attribute : attributes) {
			if (CONTEXT.equals(attribute.name())) { context = attribute.value(); }
			if (SessionMetadata.ATTRIBUTE.equals(attribute.name())) { metadata = attribute.value(); }
			if (EmailOwnershipProof.ATTRIBUTE.equals(attribute.name())) { proof=attribute.value(); }
		}
		return new Stored(row.primary(), row.created(), row.expires(), context, metadata,proof);
	}

	private record Attribute(String name, Object value) { }
	private record Stored(String primary, long created, long expires, Object context, Object metadata,Object proof) { }
	public static class EmailProofRequired extends RuntimeException {
		public EmailProofRequired(){super("Recent representative email proof required");}
	}
}
