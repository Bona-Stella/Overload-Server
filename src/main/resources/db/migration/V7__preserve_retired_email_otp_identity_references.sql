-- Only an explicit EMAIL unlink retires a legacy V5 reference. No existing data is rewritten here.
ALTER TABLE email_otp_challenges ADD COLUMN retired_reauth_identity_id UUID;
-- Keep ownership anchored after the live identity FK becomes a historical UUID reference.
ALTER TABLE email_otp_challenges ADD CONSTRAINT email_otp_reauth_user_fk FOREIGN KEY (reauth_user_id) REFERENCES users(id);
ALTER TABLE email_otp_challenges DROP CONSTRAINT email_otp_reauth_binding_check;
ALTER TABLE email_otp_challenges ADD CONSTRAINT email_otp_reauth_binding_check CHECK (
    (purpose='LOGIN' AND reauth_user_id IS NULL AND reauth_identity_id IS NULL AND reauth_contact_id IS NULL AND retired_reauth_identity_id IS NULL)
    OR (purpose='REAUTH' AND reauth_user_id IS NOT NULL AND (
        (reauth_identity_id IS NOT NULL AND reauth_contact_id IS NULL AND retired_reauth_identity_id IS NULL)
        OR (reauth_identity_id IS NULL AND reauth_contact_id IS NOT NULL AND retired_reauth_identity_id IS NULL)
        OR (reauth_identity_id IS NULL AND reauth_contact_id IS NULL AND retired_reauth_identity_id IS NOT NULL)))
    OR (purpose='CONTACT_ENROLL' AND reauth_user_id IS NOT NULL AND reauth_identity_id IS NOT NULL AND reauth_contact_id IS NULL AND retired_reauth_identity_id IS NULL)
);
