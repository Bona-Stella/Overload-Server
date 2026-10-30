-- Remove obsolete network command storage; preserve domain data and Flyway history.
-- Execute the complete migration in one PostgreSQL transaction.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE public.sync_operations
    DROP CONSTRAINT sync_operations_change_batch_fk;
DROP TABLE public.workout_change_batches;
DROP TABLE public.sync_operations;
DROP TABLE public.workout_sync_state;
ALTER TABLE public.exercise_definitions DROP COLUMN intent_stamps;
ALTER TABLE public.user_workout_settings DROP COLUMN intent_stamps;
