CREATE TABLE google_identity_change_versions (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    version BIGINT NOT NULL CHECK (version >= 0)
);

-- Preserve candidate history rather than cascading it away with a login credential.
ALTER TABLE reauth_email_candidates ADD COLUMN retired_google_identity_id UUID;
ALTER TABLE reauth_email_candidates ADD COLUMN invalidated_at TIMESTAMPTZ;
ALTER TABLE reauth_email_candidates ALTER COLUMN google_identity_id DROP NOT NULL;
ALTER TABLE reauth_email_candidates DROP CONSTRAINT reauth_email_candidates_user_id_google_identity_id_fkey;
ALTER TABLE reauth_email_candidates ADD CONSTRAINT reauth_email_candidate_identity_fk
    FOREIGN KEY (user_id,google_identity_id) REFERENCES auth_identities(user_id,id);
ALTER TABLE reauth_email_candidates ADD CHECK (
    (google_identity_id IS NOT NULL AND retired_google_identity_id IS NULL)
    OR (google_identity_id IS NULL AND retired_google_identity_id IS NOT NULL AND invalidated_at IS NOT NULL)
);

ALTER TABLE email_otp_challenges DROP CONSTRAINT email_otp_reauth_binding_check;
ALTER TABLE email_otp_challenges ADD CONSTRAINT email_otp_reauth_binding_check CHECK (
    (purpose='LOGIN' AND reauth_user_id IS NULL AND reauth_identity_id IS NULL AND reauth_contact_id IS NULL AND retired_reauth_identity_id IS NULL)
    OR (purpose='REAUTH' AND reauth_user_id IS NOT NULL AND (
        (reauth_identity_id IS NOT NULL AND reauth_contact_id IS NULL AND retired_reauth_identity_id IS NULL)
        OR (reauth_identity_id IS NULL AND reauth_contact_id IS NOT NULL AND retired_reauth_identity_id IS NULL)
        OR (reauth_identity_id IS NULL AND reauth_contact_id IS NULL AND retired_reauth_identity_id IS NOT NULL)))
    OR (purpose='CONTACT_ENROLL' AND reauth_user_id IS NOT NULL AND reauth_contact_id IS NULL AND (
        (reauth_identity_id IS NOT NULL AND retired_reauth_identity_id IS NULL)
        OR (reauth_identity_id IS NULL AND retired_reauth_identity_id IS NOT NULL)))
);
