-- Extend existing OTP state without rewriting V4 or existing LOGIN issuances.
ALTER TABLE email_otp_limits DROP CONSTRAINT email_otp_limits_purpose_check;
ALTER TABLE email_otp_limits ADD CONSTRAINT email_otp_limits_purpose_check CHECK (purpose IN ('LOGIN', 'REAUTH'));
ALTER TABLE email_otp_challenges DROP CONSTRAINT email_otp_challenges_purpose_check;
ALTER TABLE email_otp_challenges ADD CONSTRAINT email_otp_challenges_purpose_check CHECK (purpose IN ('LOGIN', 'REAUTH'));
ALTER TABLE email_otp_rate_limits DROP CONSTRAINT email_otp_rate_limits_purpose_check;
ALTER TABLE email_otp_rate_limits ADD CONSTRAINT email_otp_rate_limits_purpose_check CHECK (purpose IN ('LOGIN', 'REAUTH'));

ALTER TABLE auth_identities ADD CONSTRAINT auth_identities_owner_id_unique UNIQUE (user_id, id);
ALTER TABLE email_otp_challenges
    ADD COLUMN reauth_user_id UUID,
    ADD COLUMN reauth_identity_id UUID,
    ADD CONSTRAINT email_otp_reauth_identity_fk FOREIGN KEY (reauth_user_id, reauth_identity_id)
        REFERENCES auth_identities (user_id, id),
    ADD CONSTRAINT email_otp_reauth_binding_check CHECK (
        (purpose = 'LOGIN' AND reauth_user_id IS NULL AND reauth_identity_id IS NULL)
        OR (purpose = 'REAUTH' AND reauth_user_id IS NOT NULL AND reauth_identity_id IS NOT NULL)
    );
