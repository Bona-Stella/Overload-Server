SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE workout_sessions ADD COLUMN applied_plan_snapshot JSONB;
ALTER TABLE workout_sessions ADD COLUMN last_snapshot_hash TEXT CHECK (last_snapshot_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE workout_sessions ADD COLUMN last_snapshot_saved_at TIMESTAMPTZ;
UPDATE workout_sessions s SET applied_plan_snapshot = jsonb_build_object(
    'schemaVersion',1,
    'weightExercises',COALESCE((SELECT jsonb_agg(jsonb_build_object('sessionExerciseId',p->'sessionExerciseId',
        'setCount',p->'setCount','minReps',p->'minReps','maxReps',p->'maxReps') ORDER BY ord)
        FROM jsonb_array_elements(s.initial_plan_snapshot->'weightExercises') WITH ORDINALITY t(p,ord)),'[]'::jsonb),
    'cardioActivities',COALESCE((SELECT jsonb_agg(jsonb_build_object('sessionActivityId',p->'sessionActivityId','plan',p->'plan') ORDER BY ord)
        FROM jsonb_array_elements(s.initial_plan_snapshot->'cardioActivities') WITH ORDINALITY t(p,ord)),'[]'::jsonb));
ALTER TABLE workout_sessions ALTER COLUMN applied_plan_snapshot SET NOT NULL;
ALTER TABLE workout_sessions ADD CHECK (jsonb_typeof(applied_plan_snapshot)='object' AND COALESCE(applied_plan_snapshot->'schemaVersion'='1'::jsonb,false));

CREATE TABLE session_exercises (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('WEIGHT','CARDIO')),
    exercise_definition_id UUID,
    name_at_addition TEXT,
    UNIQUE(session_id,id,kind),
    FOREIGN KEY(user_id,data_generation,session_id) REFERENCES workout_sessions(user_id,data_generation,id),
    FOREIGN KEY(user_id,data_generation,exercise_definition_id) REFERENCES exercise_definitions(user_id,data_generation,id),
    CHECK ((kind='WEIGHT' AND exercise_definition_id IS NOT NULL) OR (kind='CARDIO' AND exercise_definition_id IS NULL AND name_at_addition IS NULL))
);
INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,exercise_definition_id)
    SELECT (p->>'sessionExerciseId')::uuid,s.id,s.user_id,s.data_generation,'WEIGHT',(p->>'exerciseDefinitionId')::uuid
    FROM workout_sessions s CROSS JOIN LATERAL jsonb_array_elements(s.initial_plan_snapshot->'weightExercises') p;
INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind)
    SELECT (p->>'sessionActivityId')::uuid,s.id,s.user_id,s.data_generation,'CARDIO'
    FROM workout_sessions s CROSS JOIN LATERAL jsonb_array_elements(s.initial_plan_snapshot->'cardioActivities') p;

CREATE TABLE session_weight_approval_requests (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    session_exercise_id UUID NOT NULL,
    kind TEXT NOT NULL DEFAULT 'WEIGHT' CHECK (kind='WEIGHT'),
    sequence BIGINT NOT NULL CHECK (sequence>0),
    previous_request_id UUID,
    expected_routine_version BIGINT NOT NULL CHECK (expected_routine_version>=0),
    previous_weight NUMERIC(20,6) NOT NULL CHECK (previous_weight>=0 AND previous_weight<100000000000000),
    next_weight NUMERIC(20,6) NOT NULL CHECK (next_weight>=0 AND next_weight<100000000000000),
    occurred_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('APPLIED','PENDING','NOT_APPLIED')),
    reason TEXT,
    routine_version_before BIGINT,
    routine_version_after BIGINT,
    resolved_at TIMESTAMPTZ,
    resolved_by_request_id UUID,
    UNIQUE(session_id,session_exercise_id,id),
    UNIQUE(session_id,session_exercise_id,sequence),
    FOREIGN KEY(session_id,session_exercise_id,kind) REFERENCES session_exercises(session_id,id,kind),
    FOREIGN KEY(session_id,session_exercise_id,previous_request_id) REFERENCES session_weight_approval_requests(session_id,session_exercise_id,id),
    FOREIGN KEY(session_id,session_exercise_id,resolved_by_request_id) REFERENCES session_weight_approval_requests(session_id,session_exercise_id,id),
    CHECK ((status='APPLIED' AND reason IS NULL AND routine_version_before IS NOT NULL AND routine_version_after IS NOT NULL AND routine_version_before>=0 AND routine_version_after>=routine_version_before AND resolved_at IS NULL AND resolved_by_request_id IS NULL)
        OR (status='PENDING' AND reason IS NOT NULL AND routine_version_before IS NULL AND routine_version_after IS NULL AND resolved_at IS NULL AND resolved_by_request_id IS NULL)
        OR (status='NOT_APPLIED' AND reason IS NOT NULL AND reason IN ('KEPT_CURRENT','REAPPROVED') AND routine_version_before IS NULL AND routine_version_after IS NULL AND resolved_at IS NOT NULL
            AND ((reason='REAPPROVED')=(resolved_by_request_id IS NOT NULL))))
);

CREATE TABLE performed_sets (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    session_exercise_id UUID NOT NULL,
    kind TEXT NOT NULL DEFAULT 'WEIGHT' CHECK(kind='WEIGHT'),
    ordinal INTEGER NOT NULL CHECK(ordinal>0),
    weight NUMERIC(20,6) NOT NULL CHECK(weight>=0 AND weight<100000000000000),
    reps INTEGER NOT NULL CHECK(reps>0),
    completed_at TIMESTAMPTZ NOT NULL,
    context_kind TEXT NOT NULL CHECK(context_kind IN ('INITIAL_APPROVED','APPROVAL_REQUEST','TODAY_ONLY')),
    approval_request_id UUID,
    approval_state_at_recording TEXT NOT NULL CHECK(approval_state_at_recording IN ('INITIAL_APPROVED','APPLIED','PENDING','NOT_APPLIED','TODAY_ONLY')),
    approved_weight_at_recording NUMERIC(20,6) CHECK(approved_weight_at_recording>=0 AND approved_weight_at_recording<100000000000000),
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY(session_id,session_exercise_id,kind) REFERENCES session_exercises(session_id,id,kind),
    FOREIGN KEY(session_id,session_exercise_id,approval_request_id) REFERENCES session_weight_approval_requests(session_id,session_exercise_id,id),
    CHECK ((context_kind='APPROVAL_REQUEST')=(approval_request_id IS NOT NULL)),
    CHECK ((context_kind='INITIAL_APPROVED' AND approval_state_at_recording='INITIAL_APPROVED' AND approved_weight_at_recording IS NOT NULL)
        OR (context_kind='TODAY_ONLY' AND approval_state_at_recording='TODAY_ONLY' AND approved_weight_at_recording IS NULL)
        OR (context_kind='APPROVAL_REQUEST' AND approval_state_at_recording IN ('APPLIED','PENDING','NOT_APPLIED')
            AND ((approval_state_at_recording='APPLIED')=(approved_weight_at_recording IS NOT NULL))))
);
CREATE UNIQUE INDEX performed_sets_active_ordinal ON performed_sets(session_exercise_id,ordinal) WHERE deleted_at IS NULL;
CREATE INDEX performed_sets_session ON performed_sets(session_id);

CREATE TABLE cardio_results (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    session_activity_id UUID NOT NULL,
    kind TEXT NOT NULL DEFAULT 'CARDIO' CHECK(kind='CARDIO'),
    status TEXT NOT NULL CHECK(status IN ('COMPLETED','SKIPPED')),
    started_at TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL,
    timer_ms BIGINT CHECK(timer_ms BETWEEN 0 AND 9007199254740991),
    active_ms BIGINT CHECK(active_ms BETWEEN 1 AND 86400000),
    corrected BOOLEAN,
    distance NUMERIC(20,6) CHECK(distance BETWEEN 0.001 AND 1000),
    average_bpm INTEGER CHECK(average_bpm BETWEEN 20 AND 300),
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY(session_id,session_activity_id,kind) REFERENCES session_exercises(session_id,id,kind),
    CHECK (started_at IS NULL OR occurred_at>=started_at),
    CHECK ((status='COMPLETED' AND started_at IS NOT NULL AND timer_ms IS NOT NULL AND active_ms IS NOT NULL AND corrected IS NOT NULL AND (corrected OR active_ms=timer_ms))
        OR (status='SKIPPED' AND timer_ms IS NULL AND active_ms IS NULL AND corrected IS NULL AND distance IS NULL AND average_bpm IS NULL))
);
CREATE UNIQUE INDEX cardio_results_active_activity ON cardio_results(session_activity_id) WHERE deleted_at IS NULL;
CREATE INDEX cardio_results_session ON cardio_results(session_id);

CREATE TABLE record_change_events (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES workout_sessions(id),
    record_kind TEXT NOT NULL CHECK(record_kind IN ('SET','CARDIO')),
    record_id UUID NOT NULL,
    change_kind TEXT NOT NULL CHECK(change_kind IN ('CORRECTED','DELETED')),
    before_value JSONB NOT NULL,
    after_value JSONB,
    recorded_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE progress_change_events DROP CONSTRAINT progress_change_events_source_check;
ALTER TABLE progress_change_events ADD COLUMN session_id UUID;
ALTER TABLE progress_change_events ADD COLUMN session_exercise_id UUID;
ALTER TABLE progress_change_events ADD COLUMN approval_request_id UUID UNIQUE;
ALTER TABLE progress_change_events ADD CHECK ((source='ROUTINE_EDITOR' AND session_id IS NULL AND session_exercise_id IS NULL AND approval_request_id IS NULL)
    OR (source='WORKOUT' AND session_id IS NOT NULL AND session_exercise_id IS NOT NULL AND approval_request_id IS NOT NULL));
ALTER TABLE progress_change_events ADD FOREIGN KEY(session_id,session_exercise_id,approval_request_id)
    REFERENCES session_weight_approval_requests(session_id,session_exercise_id,id);
CREATE INDEX progress_change_events_session ON progress_change_events(session_id) WHERE session_id IS NOT NULL;
COMMENT ON TABLE session_weight_approval_requests IS 'Immutable approval intent plus terminal resolution; not a network receipt.';
