CREATE TABLE user_reauthentication_emails (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    id UUID NOT NULL UNIQUE,
    email TEXT NOT NULL CHECK (email <> ''),
    status TEXT NOT NULL DEFAULT 'VERIFIED' CHECK (status = 'VERIFIED'),
    verified_at TIMESTAMPTZ NOT NULL,
    verification_source TEXT NOT NULL CHECK (verification_source IN ('HISTORICAL_OTP','EMAIL_LOGIN_OTP','GOOGLE_EMAIL_OTP')),
    UNIQUE (user_id, id)
);
-- Address equality is deliberately NOT unique across users. This is not a login identity.
CREATE TABLE reauth_email_candidates (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    google_identity_id UUID NOT NULL,
    google_subject TEXT NOT NULL,
    email TEXT NOT NULL CHECK (email <> ''),
    binding_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    FOREIGN KEY (user_id, google_identity_id) REFERENCES auth_identities(user_id, id) ON DELETE CASCADE,
    UNIQUE (user_id, id)
);
CREATE INDEX reauth_email_candidates_owner_index ON reauth_email_candidates(user_id);

-- Existing identity alone is not proof. Only retained, atomically consumed OTP ownership evidence qualifies.
-- Ambiguous users with several proven EMAIL addresses must use a fresh verified login to select their first contact.
WITH evidence AS (
    SELECT a.user_id, a.subject AS email, max(c.consumed_at) AS verified_at
    FROM auth_identities a JOIN email_otp_challenges c ON c.recipient=a.subject
    WHERE a.provider='email' AND a.issuer='overload:email' AND c.state='CONSUMED' AND c.accepted_at IS NOT NULL
      AND (c.purpose='LOGIN' OR (c.purpose='REAUTH' AND c.reauth_user_id=a.user_id AND c.reauth_identity_id=a.id))
    GROUP BY a.user_id, a.subject
), unambiguous AS (
    SELECT user_id FROM evidence GROUP BY user_id HAVING count(*)=1
)
INSERT INTO user_reauthentication_emails(user_id,id,email,verified_at,verification_source)
SELECT e.user_id,gen_random_uuid(),e.email,e.verified_at,'HISTORICAL_OTP' FROM evidence e JOIN unambiguous u USING(user_id);

-- V5 REAUTH was address-scoped and EMAIL identities are globally unique. Copy its budgets to
-- the new owner/address scope without changing old rows or allowing a new failure budget.
-- An old active challenge remains historical; its old binding is never accepted by the new path.
INSERT INTO email_otp_limits(email_key,purpose,last_issued_at,failed_attempts,failure_window_end)
SELECT encode(sha256(convert_to('email','UTF8') || decode('00','hex') ||
           convert_to(a.user_id::text || E'\n' || a.subject,'UTF8')),'hex'),
       'REAUTH',l.last_issued_at,l.failed_attempts,l.failure_window_end
FROM auth_identities a JOIN email_otp_limits l
  ON l.email_key=encode(sha256(convert_to('email','UTF8') || decode('00','hex') || convert_to(a.subject,'UTF8')),'hex')
WHERE a.provider='email' AND a.issuer='overload:email' AND l.purpose='REAUTH'
ON CONFLICT DO NOTHING;

INSERT INTO email_otp_rate_limits(bucket_key,purpose,window_started_at,hits)
SELECT encode(sha256(convert_to(s.scope,'UTF8') || decode('00','hex') ||
           convert_to(a.user_id::text || E'\n' || a.subject,'UTF8')),'hex'),
       'REAUTH',r.window_started_at,r.hits
FROM auth_identities a CROSS JOIN (VALUES ('request-email'),('verify-email')) AS s(scope)
JOIN email_otp_rate_limits r
  ON r.bucket_key=encode(sha256(convert_to(s.scope,'UTF8') || decode('00','hex') || convert_to(a.subject,'UTF8')),'hex')
WHERE a.provider='email' AND a.issuer='overload:email' AND r.purpose='REAUTH'
ON CONFLICT DO NOTHING;

ALTER TABLE email_otp_limits DROP CONSTRAINT email_otp_limits_purpose_check;
ALTER TABLE email_otp_limits ADD CHECK (purpose IN ('LOGIN','REAUTH','CONTACT_ENROLL'));
ALTER TABLE email_otp_challenges DROP CONSTRAINT email_otp_challenges_purpose_check;
ALTER TABLE email_otp_challenges ADD CHECK (purpose IN ('LOGIN','REAUTH','CONTACT_ENROLL'));
ALTER TABLE email_otp_rate_limits DROP CONSTRAINT email_otp_rate_limits_purpose_check;
ALTER TABLE email_otp_rate_limits ADD CHECK (purpose IN ('LOGIN','REAUTH','CONTACT_ENROLL'));
ALTER TABLE email_otp_challenges ADD COLUMN reauth_contact_id UUID;
ALTER TABLE email_otp_challenges ADD FOREIGN KEY (reauth_user_id,reauth_contact_id) REFERENCES user_reauthentication_emails(user_id,id);
ALTER TABLE email_otp_challenges DROP CONSTRAINT email_otp_reauth_binding_check;
ALTER TABLE email_otp_challenges ADD CONSTRAINT email_otp_reauth_binding_check CHECK (
    (purpose='LOGIN' AND reauth_user_id IS NULL AND reauth_identity_id IS NULL AND reauth_contact_id IS NULL)
    OR (purpose='REAUTH' AND reauth_user_id IS NOT NULL AND
        ((reauth_identity_id IS NOT NULL AND reauth_contact_id IS NULL) OR (reauth_identity_id IS NULL AND reauth_contact_id IS NOT NULL)))
    OR (purpose='CONTACT_ENROLL' AND reauth_user_id IS NOT NULL AND reauth_identity_id IS NOT NULL AND reauth_contact_id IS NULL)
);
