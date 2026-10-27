CREATE TABLE users (
    id UUID PRIMARY KEY,
    data_generation BIGINT NOT NULL DEFAULT 0,
    nickname TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_data_generation_nonnegative CHECK (data_generation >= 0)
);

COMMENT ON COLUMN users.data_generation IS
    'Server-managed workout data generation; incremented by the future reset transaction.';
COMMENT ON COLUMN users.updated_at IS
    'Initialized on insert; future server update operations must explicitly maintain this timestamp.';
