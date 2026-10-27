CREATE TABLE email_otp_limits (
    email_key CHAR(64) NOT NULL,
    purpose TEXT NOT NULL CHECK (purpose = 'LOGIN'),
    active_challenge_id UUID,
    last_issued_at TIMESTAMPTZ,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    failure_window_end TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (email_key, purpose)
);

CREATE TABLE email_otp_challenges (
    id UUID PRIMARY KEY,
    email_key CHAR(64) NOT NULL,
    purpose TEXT NOT NULL CHECK (purpose = 'LOGIN'),
    recipient TEXT NOT NULL CHECK (recipient <> ''),
    binding_hash CHAR(64) NOT NULL,
    verifier BYTEA NOT NULL CHECK (octet_length(verifier) = 32),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'FAILED', 'REVOKED', 'CONSUMED')),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > issued_at),
    accepted_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    UNIQUE (email_key, purpose, id),
    FOREIGN KEY (email_key, purpose) REFERENCES email_otp_limits (email_key, purpose),
    CHECK ((state = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CHECK (state <> 'ACTIVE' OR accepted_at IS NOT NULL)
);

ALTER TABLE email_otp_limits ADD CONSTRAINT email_otp_active_challenge_fk
    FOREIGN KEY (email_key, purpose, active_challenge_id)
    REFERENCES email_otp_challenges (email_key, purpose, id);

CREATE INDEX email_otp_challenges_expiry_index ON email_otp_challenges (expires_at);

CREATE TABLE email_otp_login_claims (
    binding_hash CHAR(64) PRIMARY KEY,
    challenge_id UUID NOT NULL UNIQUE REFERENCES email_otp_challenges (id),
    claimed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE email_otp_rate_limits (
    bucket_key CHAR(64) NOT NULL,
    purpose TEXT NOT NULL CHECK (purpose = 'LOGIN'),
    window_started_at TIMESTAMPTZ NOT NULL,
    hits INTEGER NOT NULL CHECK (hits >= 0),
    PRIMARY KEY (bucket_key, purpose)
);
