package com.overload.overload_server.security;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Server-owned evidence, independent of the backwards-compatible SessionMetadata timestamp. */
public record EmailOwnershipProof(UUID userId, String sessionId, UUID contactId, String emailHash,
        Instant verifiedAt, Source source) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String ATTRIBUTE = EmailOwnershipProof.class.getName();
    public enum Source { EMAIL_LOGIN, EMAIL_REAUTH, CONTACT_ENROLL }

    public boolean matches(UUID user, String session, UUID contact, String email, Instant now) {
        return boundTo(user,session,now) && contact.equals(contactId) && digest(email).equals(emailHash);
    }

    public boolean boundTo(UUID user,String session,Instant now) {
        return user.equals(userId) && session.equals(sessionId) && contactId!=null && emailHash!=null
                && source != null && verifiedAt != null
                && !verifiedAt.isAfter(now) && !verifiedAt.isBefore(now.minus(SessionPolicy.RECENT_REAUTHENTICATION));
    }

    public EmailOwnershipProof rebind(String session) {
        return new EmailOwnershipProof(userId, session, contactId, emailHash, verifiedAt, source);
    }

    public static Verification verified(UUID contact, String email, Instant at, Source source) {
        return new Verification(contact, digest(email), at, source);
    }

    public record Verification(UUID contactId, String emailHash, Instant verifiedAt, Source source) {
        public EmailOwnershipProof bind(UUID user, String session) {
            return new EmailOwnershipProof(user, session, contactId, emailHash, verifiedAt, source);
        }
        @Override public String toString() { return "EmailVerification[redacted]"; }
    }

    private static String digest(String email) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    @Override public String toString() { return "EmailOwnershipProof[redacted]"; }
}
