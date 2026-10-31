-- Online routine aggregates only. Existing domain rows and migration history are unchanged.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE routines (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    name TEXT NOT NULL CHECK (name !~ '^[[:space:]]*$'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    UNIQUE (user_id, data_generation, id),
    FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)
);

CREATE TABLE routine_weight_placements (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    routine_id UUID NOT NULL,
    exercise_definition_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    set_count INTEGER NOT NULL CHECK (set_count > 0),
    min_reps INTEGER NOT NULL CHECK (min_reps > 0),
    max_reps INTEGER NOT NULL CHECK (max_reps >= min_reps),
    initial_weight NUMERIC(20,6) NOT NULL CHECK (initial_weight >= 0 AND initial_weight < 100000000000000),
    approved_weight NUMERIC(20,6) NOT NULL CHECK (approved_weight >= 0 AND approved_weight < 100000000000000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    UNIQUE (user_id, data_generation, routine_id, id),
    FOREIGN KEY (user_id, data_generation, routine_id) REFERENCES routines(user_id, data_generation, id),
    FOREIGN KEY (user_id, data_generation, exercise_definition_id) REFERENCES exercise_definitions(user_id, data_generation, id)
);
CREATE UNIQUE INDEX routine_weight_active_position ON routine_weight_placements(routine_id, position) WHERE deleted_at IS NULL;

CREATE TABLE routine_cardio_placements (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    routine_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    kind TEXT NOT NULL CHECK (kind IN ('TREADMILL','BIKE','OUTDOOR','STAIRS','OTHER')),
    name TEXT NOT NULL CHECK (name !~ '^[[:space:]]*$' AND char_length(name) <= 100),
    target_seconds INTEGER NOT NULL CHECK (target_seconds BETWEEN 1 AND 86400),
    target_distance NUMERIC(20,6) CHECK (target_distance BETWEEN 0.001 AND 1000),
    intensity_mode TEXT,
    intensity_value NUMERIC(20,6),
    incline NUMERIC(20,6) CHECK (incline BETWEEN 0 AND 50),
    resistance NUMERIC(20,6) CHECK (resistance BETWEEN 1 AND 100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (user_id, data_generation, routine_id) REFERENCES routines(user_id, data_generation, id),
    CHECK ((intensity_mode IS NULL AND intensity_value IS NULL) OR
        (intensity_mode IS NOT NULL AND intensity_value IS NOT NULL AND
            ((intensity_mode = 'SPEED' AND intensity_value BETWEEN 0.1 AND 100) OR
             (intensity_mode = 'PACE' AND intensity_value BETWEEN 0.1 AND 120)))),
    CHECK (kind IN ('TREADMILL','BIKE','OUTDOOR') OR (target_distance IS NULL AND intensity_mode IS NULL)),
    CHECK (kind = 'TREADMILL' OR incline IS NULL),
    CHECK (kind = 'BIKE' OR resistance IS NULL)
);
CREATE UNIQUE INDEX routine_cardio_active_position ON routine_cardio_placements(routine_id, position) WHERE deleted_at IS NULL;

CREATE TABLE progress_change_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    routine_id UUID NOT NULL,
    placement_id UUID NOT NULL,
    routine_name TEXT NOT NULL,
    exercise_name TEXT NOT NULL,
    previous_weight NUMERIC(20,6) NOT NULL CHECK (previous_weight >= 0 AND previous_weight < 100000000000000),
    next_weight NUMERIC(20,6) NOT NULL CHECK (next_weight >= 0 AND next_weight < 100000000000000),
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL CHECK (source = 'ROUTINE_EDITOR'),
    CHECK (previous_weight <> next_weight),
    FOREIGN KEY (user_id, data_generation, routine_id, placement_id)
        REFERENCES routine_weight_placements(user_id, data_generation, routine_id, id)
);
COMMENT ON TABLE progress_change_events IS 'Immutable approval facts, not network request receipts. No update/delete API.';
COMMENT ON COLUMN routine_weight_placements.initial_weight IS 'Set only on creation; preserved through explicit restoration.';
COMMENT ON COLUMN routines.version IS 'Incremented once per actual aggregate change under the routine row lock.';
