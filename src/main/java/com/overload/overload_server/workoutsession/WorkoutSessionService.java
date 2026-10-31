package com.overload.overload_server.workoutsession;

import com.overload.overload_server.routine.RoutineRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

@Service
public class WorkoutSessionService {
    private final WorkoutSessionRepository sessions;
    private final RoutineRepository routines;
    private final Clock clock;
    private final TransactionTemplate starts, writes, reads;

    WorkoutSessionService(WorkoutSessionRepository sessions, RoutineRepository routines, ObjectProvider<Clock> clocks,
                          @Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.sessions = sessions; this.routines = routines; this.clock = clocks.getIfAvailable(Clock::systemUTC);
        starts = new TransactionTemplate(manager); starts.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager); reads.setReadOnly(true); reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    WorkoutSessionResponses.Started start(UUID owner, WorkoutSessionRequests.Start input) {
        return transaction(starts, () -> {
            owner(owner, input.dataGeneration());
            var previous = sessions.find(owner, input.dataGeneration(), input.id(), false);
            if (previous.isPresent()) {
                if (!previous.get().sameStart(input)) throw new WorkoutSessionFailure(SESSION_ID_CONFLICT);
                return new WorkoutSessionResponses.Started(false, WorkoutSessionResponses.detail(previous.get()));
            }
            if (sessions.occupied(input.id())) throw new WorkoutSessionFailure(SESSION_ID_CONFLICT);
            if (sessions.active(owner, input.dataGeneration()).isPresent()) throw new WorkoutSessionFailure(ACTIVE_SESSION_EXISTS);
            var source = routines.planForWorkoutStart(owner, input.dataGeneration(), input.routineId())
                    .orElseThrow(() -> new WorkoutSessionFailure(ROUTINE_NOT_FOUND));
            var session = WorkoutSession.start(owner, input, InitialPlan.copy(source), clock.instant());
            sessions.insert(session); sessions.flush();
            return new WorkoutSessionResponses.Started(true, WorkoutSessionResponses.detail(session));
        });
    }
    WorkoutSessionResponses.Active active(UUID owner) {
        return transaction(reads, () -> {
            long generation = sessions.generation(owner, false);
            return new WorkoutSessionResponses.Active(owner, Long.toString(generation),
                    sessions.active(owner, generation).map(WorkoutSessionResponses::detail).orElse(null));
        });
    }
    WorkoutSessionResponses.Detail detail(UUID owner, UUID id) {
        return transaction(reads, () -> WorkoutSessionResponses.detail(find(owner, sessions.generation(owner, false), id, false)));
    }
    WorkoutSessionResponses.Detail takeover(UUID owner, UUID id, WorkoutSessionRequests.Takeover input) {
        return transaction(writes, () -> {
            owner(owner, input.dataGeneration());
            var session = find(owner, input.dataGeneration(), id, true);
            session.takeover(input, clock.instant()); sessions.flush();
            return WorkoutSessionResponses.detail(session);
        });
    }
    private void owner(UUID owner, long expected) {
        if (sessions.generation(owner, true) != expected) throw new WorkoutSessionFailure(DATA_GENERATION_MISMATCH);
    }
    private WorkoutSession find(UUID owner, long generation, UUID id, boolean lock) {
        return sessions.find(owner, generation, id, lock).orElseThrow(() -> new WorkoutSessionFailure(SESSION_NOT_FOUND));
    }
    private static <T> T transaction(TransactionTemplate template, Supplier<T> action) {
        var original = new AtomicReference<RuntimeException>();
        try {
            return template.execute(status -> {
                try { return action.get(); } catch (RuntimeException failure) { original.set(failure); throw failure; }
            });
        } catch (RuntimeException failure) {
            if (original.get() != null && original.get() != failure) failure.addSuppressed(original.get());
            throw failure;
        }
    }
}
