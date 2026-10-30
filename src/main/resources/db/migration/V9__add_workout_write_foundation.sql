-- Additive only: the existing JDBC provisioning INSERTs keep their defaults and timestamps.
ALTER TABLE exercise_definitions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN intent_stamps JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(intent_stamps) = 'object');

ALTER TABLE user_workout_settings
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN intent_stamps JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(intent_stamps) = 'object');

CREATE TABLE workout_sync_state (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    committed_revision BIGINT NOT NULL DEFAULT 0 CHECK (committed_revision >= 0),
    PRIMARY KEY (user_id, data_generation),
    FOREIGN KEY (user_id, data_generation) REFERENCES users (id, data_generation)
);

-- Only final outcomes are persisted. 1B must lock users first, check the key, and insert
-- the final receipt in the domain transaction. No independently committed reservation.
CREATE TABLE sync_operations (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    operation_id UUID NOT NULL,
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    command_schema_version SMALLINT NOT NULL CHECK (command_schema_version = 1),
    command_type TEXT NOT NULL CHECK (command_type ~ '^[A-Z][A-Z0-9_]*$'),
    state TEXT NOT NULL CHECK (state IN ('SUCCEEDED', 'REJECTED')),
    http_status SMALLINT NOT NULL,
    ack_body JSONB,
    rejection_code TEXT,
    intent_command JSONB,
    origin_operation_id UUID,
    origin_fingerprint TEXT CHECK (origin_fingerprint ~ '^[0-9a-f]{64}$'),
    change_revision BIGINT CHECK (change_revision > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, data_generation, operation_id),
    UNIQUE (user_id, data_generation, operation_id, change_revision),
    FOREIGN KEY (user_id, data_generation) REFERENCES users (id, data_generation),
    FOREIGN KEY (user_id, data_generation, origin_operation_id)
        REFERENCES sync_operations (user_id, data_generation, operation_id),
    CHECK (get_byte(uuid_send(operation_id), 6) >> 4 = 4
        AND get_byte(uuid_send(operation_id), 8) & 192 = 128),
    CHECK ((origin_operation_id IS NULL) = (origin_fingerprint IS NULL)),
    CHECK (origin_operation_id IS NULL OR origin_operation_id <> operation_id),
    CONSTRAINT sync_operations_outcome_check CHECK (
        (state = 'SUCCEEDED' AND http_status = 200
            AND ack_body IS NOT NULL AND jsonb_typeof(ack_body) = 'object'
            AND rejection_code IS NULL AND intent_command IS NULL)
        OR
        (state = 'REJECTED' AND http_status = 409 AND ack_body IS NULL
            AND rejection_code IS NOT NULL
            AND rejection_code IN ('VERSION_CONFLICT', 'CONFLICT_CONFIRMATION_REQUIRED')
            AND intent_command IS NOT NULL AND jsonb_typeof(intent_command) = 'object'
            AND change_revision IS NULL)
    ),
    CONSTRAINT sync_operations_ack_scope_check CHECK (ack_body IS NULL OR (
        ack_body ?& ARRAY['ackSchemaVersion','userId','dataGeneration','operationId','fingerprint','resultVersions','result']
        AND ack_body->'ackSchemaVersion' = '1'::jsonb
        AND jsonb_typeof(ack_body->'userId') = 'string' AND ack_body->>'userId' = user_id::text
        AND jsonb_typeof(ack_body->'dataGeneration') = 'string' AND ack_body->>'dataGeneration' = data_generation::text
        AND jsonb_typeof(ack_body->'operationId') = 'string' AND ack_body->>'operationId' = operation_id::text
        AND jsonb_typeof(ack_body->'fingerprint') = 'string' AND ack_body->>'fingerprint' = fingerprint
        AND jsonb_typeof(ack_body->'resultVersions') = 'array'
        AND jsonb_typeof(ack_body->'result') = 'object'
    )),
    CONSTRAINT sync_operations_intent_scope_check CHECK (intent_command IS NULL OR (
        intent_command ?& ARRAY['commandSchemaVersion','expectedUserId','dataGeneration','operationId','type']
        AND intent_command->'commandSchemaVersion' = '1'::jsonb
        AND jsonb_typeof(intent_command->'expectedUserId') = 'string' AND intent_command->>'expectedUserId' = user_id::text
        AND jsonb_typeof(intent_command->'dataGeneration') = 'string' AND intent_command->>'dataGeneration' = data_generation::text
        AND jsonb_typeof(intent_command->'operationId') = 'string' AND intent_command->>'operationId' = operation_id::text
        AND jsonb_typeof(intent_command->'type') = 'string' AND intent_command->>'type' = command_type
    ))
);

CREATE TABLE workout_change_batches (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    operation_id UUID NOT NULL,
    changes JSONB NOT NULL CHECK (
        CASE WHEN jsonb_typeof(changes) = 'array' THEN jsonb_array_length(changes) > 0 ELSE false END),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, data_generation, revision),
    UNIQUE (user_id, data_generation, operation_id),
    UNIQUE (user_id, data_generation, revision, operation_id),
    FOREIGN KEY (user_id, data_generation) REFERENCES workout_sync_state (user_id, data_generation),
    FOREIGN KEY (user_id, data_generation, operation_id, revision)
        REFERENCES sync_operations (user_id, data_generation, operation_id, change_revision)
        DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE sync_operations ADD CONSTRAINT sync_operations_change_batch_fk
    FOREIGN KEY (user_id, data_generation, change_revision, operation_id)
    REFERENCES workout_change_batches (user_id, data_generation, revision, operation_id)
    DEFERRABLE INITIALLY DEFERRED;

COMMENT ON TABLE sync_operations IS
    'Final command outcomes only; user row lock, validation and domain changes share workoutTransactionManager.';
COMMENT ON COLUMN workout_sync_state.committed_revision IS
    'Allocated under the user lock in the domain transaction, never from a sequence or wall clock.';
