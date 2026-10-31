SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE workout_sessions ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE workout_sessions ADD CHECK (deleted_at IS NULL OR status IN ('FINISHED','AUTO_CLOSED'));
ALTER TABLE session_weight_approval_requests ADD COLUMN created_after_closure BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN session_weight_approval_requests.created_after_closure IS 'New post-closure approval cannot establish an earlier recovered performance context.';

ALTER TABLE performed_sets DROP CONSTRAINT performed_sets_context_kind_check;
ALTER TABLE performed_sets DROP CONSTRAINT performed_sets_approval_state_at_recording_check;
ALTER TABLE performed_sets DROP CONSTRAINT performed_sets_check1;
ALTER TABLE performed_sets ADD CHECK (context_kind IN ('INITIAL_APPROVED','APPROVAL_REQUEST','TODAY_ONLY','UNVERIFIED'));
ALTER TABLE performed_sets ADD CHECK (approval_state_at_recording IN ('INITIAL_APPROVED','APPLIED','PENDING','NOT_APPLIED','TODAY_ONLY','UNVERIFIED'));
ALTER TABLE performed_sets ADD CHECK (
    (context_kind='INITIAL_APPROVED' AND approval_state_at_recording='INITIAL_APPROVED' AND approved_weight_at_recording IS NOT NULL)
    OR (context_kind='TODAY_ONLY' AND approval_state_at_recording='TODAY_ONLY' AND approved_weight_at_recording IS NULL)
    OR (context_kind='UNVERIFIED' AND approval_state_at_recording='UNVERIFIED' AND approved_weight_at_recording IS NULL)
    OR (context_kind='APPROVAL_REQUEST' AND approval_state_at_recording IN ('APPLIED','PENDING','NOT_APPLIED')
        AND ((approval_state_at_recording='APPLIED')=(approved_weight_at_recording IS NOT NULL)))
);

ALTER TABLE record_change_events DROP CONSTRAINT record_change_events_record_kind_check;
ALTER TABLE record_change_events DROP CONSTRAINT record_change_events_change_kind_check;
ALTER TABLE record_change_events ALTER COLUMN before_value DROP NOT NULL;
ALTER TABLE record_change_events ADD COLUMN change_context JSONB CHECK (change_context IS NULL OR
    (jsonb_typeof(change_context)='object' AND COALESCE(change_context->'schemaVersion'='1'::jsonb,false)));
ALTER TABLE record_change_events ADD CHECK (record_kind IN ('SET','CARDIO','SESSION'));
ALTER TABLE record_change_events ADD CHECK (change_kind IN ('CORRECTED','DELETED','RECOVERED'));
ALTER TABLE record_change_events ADD CHECK (
    (change_kind='RECOVERED' AND record_kind IN ('SET','CARDIO') AND before_value IS NULL AND after_value IS NOT NULL AND change_context IS NOT NULL)
    OR (change_kind<>'RECOVERED' AND before_value IS NOT NULL)
);
CREATE INDEX record_change_events_session_time ON record_change_events(session_id,recorded_at,id);
