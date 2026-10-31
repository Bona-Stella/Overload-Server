package com.overload.overload_server.workoutsession;

final class WorkoutSessionFailure extends RuntimeException {
    enum Reason {
        INVALID_REQUEST(400), AUTHENTICATION_REQUIRED(401), SESSION_NOT_FOUND(404), ROUTINE_NOT_FOUND(404),
        DATA_GENERATION_MISMATCH(409), SESSION_ID_CONFLICT(409), ACTIVE_SESSION_EXISTS(409),
        EDITOR_OWNERSHIP_LOST(409), SESSION_NOT_ACTIVE(409), WRITE_CONFLICT(409), CLOCK_CONTEXT_INVALID(422);

        final int status;
        Reason(int status) { this.status = status; }
    }

    final Reason reason;
    WorkoutSessionFailure(Reason reason) { super(reason.name()); this.reason = reason; }
}
