package com.overload.overload_server.workoutsession;

import java.time.*;
import java.util.UUID;

public final class WorkoutSessionResponses {
    private WorkoutSessionResponses() { }
    public record Detail(UUID userId, String dataGeneration, UUID id, String status, String version,
                         Instant startedAt, LocalDate workoutDate, String startedTimeZone, UUID editorDeviceId,
                         String writerEpoch, String localRevision, Instant lastWorkoutSyncedAt, InitialPlan initialPlan) { }
    public record Active(UUID userId, String dataGeneration, Detail session) { }
    record Started(boolean created, Detail session) { }
    static Detail detail(WorkoutSession value) {
        return new Detail(value.userId, Long.toString(value.dataGeneration), value.id, value.status, Long.toString(value.version),
                value.startedAt, value.workoutDate, value.startedTimeZone, value.editorDeviceId, Long.toString(value.writerEpoch),
                Long.toString(value.localRevision), value.lastWorkoutSyncedAt, InitialPlan.read(value.initialPlan));
    }
}
