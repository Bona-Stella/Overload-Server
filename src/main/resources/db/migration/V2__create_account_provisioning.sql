ALTER TABLE users ADD CONSTRAINT users_id_generation_unique UNIQUE (id, data_generation);

CREATE TABLE auth_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    provider TEXT NOT NULL,
    issuer TEXT NOT NULL CHECK (issuer !~ '^[[:space:]]*$'),
    subject TEXT NOT NULL CHECK (subject !~ '^[[:space:]]*$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_identities_provider_issuer CHECK (
        (provider = 'google' AND issuer = 'https://accounts.google.com') OR
        (provider = 'email' AND issuer = 'overload:email')
    ),
    CONSTRAINT auth_identities_credential_unique UNIQUE (provider, issuer, subject)
);

CREATE INDEX auth_identities_user_id_index ON auth_identities (user_id);

CREATE TABLE exercise_definitions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    name TEXT NOT NULL CHECK (name !~ '^[[:space:]]*$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT exercise_definitions_owner_generation_fk
        FOREIGN KEY (user_id, data_generation) REFERENCES users (id, data_generation),
    CONSTRAINT exercise_definitions_owner_generation_id_unique UNIQUE (user_id, data_generation, id)
);

CREATE TABLE user_workout_settings (
    user_id UUID PRIMARY KEY,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    rest_low_seconds INTEGER NOT NULL CHECK (rest_low_seconds > 0),
    rest_medium_seconds INTEGER NOT NULL CHECK (rest_medium_seconds > 0),
    rest_high_seconds INTEGER NOT NULL CHECK (rest_high_seconds > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_workout_settings_owner_generation_fk
        FOREIGN KEY (user_id, data_generation) REFERENCES users (id, data_generation)
);

COMMENT ON CONSTRAINT exercise_definitions_owner_generation_id_unique ON exercise_definitions IS
    'References to an exercise must carry its owner and data generation, not just its UUID.';
