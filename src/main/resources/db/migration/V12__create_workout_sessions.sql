-- Stage 1 only: no performed sets, approvals, snapshots, finish or expiry worker yet.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE workout_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    source_routine_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status = 'ACTIVE'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    started_at TIMESTAMPTZ NOT NULL,
    workout_date DATE NOT NULL,
    started_time_zone TEXT NOT NULL,
    started_device_id UUID NOT NULL,
    editor_device_id UUID NOT NULL,
    writer_epoch BIGINT NOT NULL DEFAULT 0 CHECK (writer_epoch >= 0),
    local_revision BIGINT NOT NULL DEFAULT 0 CHECK (local_revision >= 0),
    initial_plan_snapshot JSONB NOT NULL CHECK (
        jsonb_typeof(initial_plan_snapshot) = 'object'
        AND COALESCE(initial_plan_snapshot->'schemaVersion' = '1'::jsonb, false)
    ),
    last_workout_synced_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, data_generation, id),
    FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation),
    FOREIGN KEY (user_id, data_generation, source_routine_id)
        REFERENCES routines(user_id, data_generation, id)
);
-- Deliberately independent of data_generation: at most one ACTIVE per account.
CREATE UNIQUE INDEX workout_sessions_one_active_user ON workout_sessions(user_id) WHERE status = 'ACTIVE';

CREATE FUNCTION preserve_workout_session_initial_plan() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.user_id, NEW.data_generation, NEW.source_routine_id,
           NEW.started_at, NEW.workout_date, NEW.started_time_zone, NEW.started_device_id,
           NEW.initial_plan_snapshot, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.user_id, OLD.data_generation, OLD.source_routine_id,
           OLD.started_at, OLD.workout_date, OLD.started_time_zone, OLD.started_device_id,
           OLD.initial_plan_snapshot, OLD.created_at) THEN
        RAISE EXCEPTION 'Immutable workout start facts' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workout_sessions_immutable_start BEFORE UPDATE ON workout_sessions
    FOR EACH ROW EXECUTE FUNCTION preserve_workout_session_initial_plan();
COMMENT ON COLUMN workout_sessions.initial_plan_snapshot IS 'Sole authoritative initial plan; includes new session exercise UUIDs and distinct source placement UUIDs.';
COMMENT ON COLUMN workout_sessions.last_workout_synced_at IS 'START or actual workout data save only; takeover and reads never extend the deadline.';
