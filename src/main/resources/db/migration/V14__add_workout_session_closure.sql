SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE workout_sessions DROP CONSTRAINT workout_sessions_status_check;
ALTER TABLE workout_sessions ADD COLUMN ended_at TIMESTAMPTZ;
ALTER TABLE workout_sessions ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE workout_sessions ADD COLUMN completion_assessment_snapshot JSONB;
ALTER TABLE workout_sessions ADD CHECK (status IN ('ACTIVE','FINISHED','AUTO_CLOSED'));
ALTER TABLE workout_sessions ADD CONSTRAINT workout_sessions_closure_check CHECK (
    (status='ACTIVE' AND ended_at IS NULL AND closed_at IS NULL AND completion_assessment_snapshot IS NULL)
    OR (status='FINISHED' AND ended_at IS NOT NULL AND ended_at>=started_at AND closed_at IS NOT NULL
        AND completion_assessment_snapshot IS NOT NULL AND jsonb_typeof(completion_assessment_snapshot)='object'
        AND COALESCE(completion_assessment_snapshot->'schemaVersion'='1'::jsonb,false))
    OR (status='AUTO_CLOSED' AND ended_at IS NULL AND closed_at IS NOT NULL AND completion_assessment_snapshot IS NULL)
);
CREATE INDEX workout_sessions_active_expiry ON workout_sessions(last_workout_synced_at,id) WHERE status='ACTIVE';

CREATE FUNCTION preserve_workout_session_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status<>'ACTIVE' AND ROW(NEW.status,NEW.ended_at,NEW.closed_at,NEW.completion_assessment_snapshot,
            NEW.applied_plan_snapshot,NEW.editor_device_id,NEW.writer_epoch,NEW.local_revision,
            NEW.last_snapshot_hash,NEW.last_snapshot_saved_at,NEW.last_workout_synced_at)
        IS DISTINCT FROM ROW(OLD.status,OLD.ended_at,OLD.closed_at,OLD.completion_assessment_snapshot,
            OLD.applied_plan_snapshot,OLD.editor_device_id,OLD.writer_epoch,OLD.local_revision,
            OLD.last_snapshot_hash,OLD.last_snapshot_saved_at,OLD.last_workout_synced_at) THEN
        RAISE EXCEPTION 'Immutable workout closure facts' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workout_sessions_immutable_closure BEFORE UPDATE ON workout_sessions
    FOR EACH ROW EXECUTE FUNCTION preserve_workout_session_closure();
COMMENT ON COLUMN workout_sessions.closed_at IS 'Server closure time, never a substitute for actual user ended_at or workout duration.';
COMMENT ON COLUMN workout_sessions.completion_assessment_snapshot IS 'Immutable first explicit finish assessment; NULL for AUTO_CLOSED.';
