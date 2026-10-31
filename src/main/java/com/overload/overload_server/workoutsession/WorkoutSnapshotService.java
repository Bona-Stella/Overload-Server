package com.overload.overload_server.workoutsession;

import com.overload.overload_server.routine.RoutineRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

@Service
public class WorkoutSnapshotService {
    private final WorkoutSessionRepository sessions;
    private final WorkoutSnapshotRepository data;
    private final RoutineRepository routines;
    private final Clock clock;
    private final TransactionTemplate writes;
    WorkoutSnapshotService(WorkoutSessionRepository sessions, WorkoutSnapshotRepository data, RoutineRepository routines, ObjectProvider<Clock> clocks,
        @Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.sessions = sessions;
        this.data = data;
        this.routines = routines;
        clock = clocks.getIfAvailable(Clock::systemUTC);
        writes = new TransactionTemplate(manager);
    }

    Ack save(UUID owner, UUID id, Save input) {
        return transaction(() -> {
            owner(owner, input.dataGeneration());
            UUID source = data.source(owner, input.dataGeneration(), id);
            // scalar only: never caches the session before its lock
            var scope = input.snapshot().approvalRequests().isEmpty()?null:routines.lockForWorkoutApproval(owner, input.dataGeneration(), source);
            var definitions = data.definitions(owner, input.dataGeneration(), input.snapshot().sessionExercises().stream().filter(e -> e.kind().equals("WEIGHT")).map(Exercise::exerciseDefinitionId).distinct().toList());
            var session = find(owner, input.dataGeneration(), id);
            guard(session, input.editorDeviceId(), input.writerEpoch());
            String hash = hash(input);
            if (input.localRevision()<session.localRevision)throw failure(REVISION_STALE);
            if (input.localRevision()==session.localRevision) {
                if (!hash.equals(session.lastSnapshotHash))throw failure(REVISION_PAYLOAD_MISMATCH);
                return ack(session, true);
            }
            incrementable(session);
            var items = index(data.exercises(id), e -> e.id);
            for (var item:input.snapshot().sessionExercises()) {
                var old = items.get(item.id());
                if (old!=null) {
                    if (!old.value().equals(item))throw failure(IMMUTABLE_RECORD_CONTEXT);
                }
                else {
                    available("session_exercises", item.id());
                    String name = null;
                    if (item.kind().equals("WEIGHT")) {
                        var definition = definitions.get(item.exerciseDefinitionId());
                        if (definition==null||definition.getDeletedAt()!=null)throw failure(EXERCISE_NOT_FOUND);
                        name = definition.getName();
                    }
                    var added = new SessionExercise(session, item, name);
                    data.insert(added);
                    items.put(added.id, added);
                }
            }
            validatePlan(input.snapshot().appliedPlan(), items);
            var approvals = index(data.approvals(id), r -> r.id);
            var applied = approvals(session, input.snapshot().approvalRequests(), items, approvals, scope, false);
            boolean changed = facts(session, input.snapshot(), items, approvals);
            var oldPlan = JSON.readValue(session.appliedPlan, Plan.class);
            if (!oldPlan.equals(input.snapshot().appliedPlan())) {
                session.appliedPlan = json(input.snapshot().appliedPlan());
                changed = true;
            }
            completeApprovals(scope, applied);
            Instant now = clock.instant();
            session.version++;
            session.updatedAt = now;
            session.localRevision = input.localRevision();
            session.lastSnapshotHash = hash;
            session.lastSnapshotSavedAt = now;
            if (changed)session.lastWorkoutSyncedAt = now;
            data.flush();
            return ack(session, false);
            });
    }

    Resolution resolve(UUID owner, UUID id, Resolve input) {
        return transaction(() -> {
            owner(owner, input.dataGeneration());
            UUID source = data.source(owner, input.dataGeneration(), id);
            var scope = input.decision().equals("REAPPROVE")?routines.lockForWorkoutApproval(owner, input.dataGeneration(), source):null;
            var session = find(owner, input.dataGeneration(), id);
            guard(session, input.editorDeviceId(), input.writerEpoch());
            var requests = index(data.approvals(id), r -> r.id);
            var selected = new ArrayList<WeightApprovalRequest>();
            for (UUID request:input.pendingRequestIds()) {
                var row = requests.get(request);
                if (row==null||!row.exerciseId.equals(input.sessionExerciseId()))throw failure(RESOLUTION_CONFLICT);
                selected.add(row);
            }
            // KEEP_CURRENT needs no receipt: immutable resolution facts remain queryable in the session.
            boolean confirmed = selected.stream().allMatch(r -> "NOT_APPLIED".equals(r.status)&&Objects.equals(r.resolvedAt, input.occurredAt())
                &&(input.newApprovalRequest()==null?"KEPT_CURRENT".equals(r.reason)&&r.resolvedBy==null:
                "REAPPROVED".equals(r.reason)&&input.newApprovalRequest().id().equals(r.resolvedBy)));
            if (confirmed) {
                if (input.newApprovalRequest()!=null) {
                    var row = requests.get(input.newApprovalRequest().id());
                    if (row==null||!row.value().equals(input.newApprovalRequest()))throw failure(APPROVAL_REQUEST_ID_CONFLICT);
                }
                return resolution(session);
            }
            if (session.version!=input.expectedSessionVersion()||session.localRevision!=input.expectedLocalRevision())throw failure(RESOLUTION_CONFLICT);
            var pending = requests.values().stream().filter(r -> r.exerciseId.equals(input.sessionExerciseId())&&r.status.equals("PENDING")).map(r -> r.id).collect(Collectors.toSet());
            if (!pending.equals(new HashSet<>(input.pendingRequestIds())))throw failure(RESOLUTION_CONFLICT);
            incrementable(session);
            afterStart(session, input.occurredAt());
            for (var row:selected)if (input.occurredAt().isBefore(row.occurredAt))throw failure(CLOCK_CONTEXT_INVALID);
            if (input.newApprovalRequest()!=null) {
                var initial = initialWeight(session, input.sessionExerciseId());
                if (initial==null||!scope.available(initial.sourcePlacementId()))throw failure(SOURCE_NOT_APPROVABLE);
                if (scope.version()!=input.newApprovalRequest().expectedRoutineVersion()
                    ||scope.weight(initial.sourcePlacementId()).compareTo(new BigDecimal(input.newApprovalRequest().previousApprovedWeightKg()))!=0)throw failure(RESOLUTION_CONFLICT);
            }
            for (var row:selected) {
                row.status = "NOT_APPLIED";
                row.reason = input.newApprovalRequest()==null?"KEPT_CURRENT":"REAPPROVED";
                row.resolvedAt = input.occurredAt();
                row.resolvedBy = input.newApprovalRequest()==null?null:input.newApprovalRequest().id();
            }
            if (input.newApprovalRequest()!=null) {
                var added = approvals(session, List.of(input.newApprovalRequest()), index(data.exercises(id), e -> e.id), requests, scope, true);
                if (added.size()!=1)throw failure(RESOLUTION_CONFLICT);
                completeApprovals(scope, added);
            }
            session.version++;
            session.updatedAt = clock.instant();
            data.flush();
            return resolution(session);
            });
    }

    private List<WeightApprovalRequest> approvals(WorkoutSession session, List<Approval> inputs, Map<UUID, SessionExercise> items,
        Map<UUID, WeightApprovalRequest> requests, RoutineRepository.ApprovalScope scope, boolean resolution) {
        long before = scope==null?0:scope.version();
        var applied = new ArrayList<WeightApprovalRequest>();
        for (var input:inputs) {
            var old = requests.get(input.id());
            if (old!=null) {
                if (!old.value().equals(input))throw failure(APPROVAL_REQUEST_ID_CONFLICT);
                continue;
            }
            available("session_weight_approval_requests", input.id());
            item(items, input.sessionExerciseId(), "WEIGHT");
            afterStart(session, input.occurredAt());
            long max = requests.values().stream().filter(r -> r.exerciseId.equals(input.sessionExerciseId())).mapToLong(r -> r.sequence).max().orElse(0);
            if (max==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);
            if (input.sequence()<=max)throw failure(APPROVAL_SEQUENCE_CONFLICT);
            if (input.sequence()!=max+1)throw failure(APPROVAL_SEQUENCE_GAP);
            if (requests.values().stream().anyMatch(r -> r.exerciseId.equals(input.sessionExerciseId())&&input.occurredAt().isBefore(r.occurredAt)))throw failure(CLOCK_CONTEXT_INVALID);
            WeightApprovalRequest previous = input.previousRequestId()==null?null:requests.get(input.previousRequestId());
            if (input.previousRequestId()!=null&&(previous==null||!previous.exerciseId.equals(input.sessionExerciseId())||previous.sequence>=input.sequence()
                ||previous.nextWeight.compareTo(new BigDecimal(input.previousApprovedWeightKg()))!=0))throw failure(INVALID_SNAPSHOT);
            if (previous!=null&&input.occurredAt().isBefore(previous.occurredAt))throw failure(CLOCK_CONTEXT_INVALID);
            var row = new WeightApprovalRequest(session.id, input);
            var initial = initialWeight(session, row.exerciseId);
            String reason = null;
            if (initial==null)reason = "NO_SOURCE_PLACEMENT";
            else if (scope==null||!scope.available(initial.sourcePlacementId()))reason = "SOURCE_REMOVED";
            else if (previous!=null&&!previous.status.equals("APPLIED"))reason = previous.status.equals("PENDING")?"PREDECESSOR_PENDING":"PREDECESSOR_NOT_APPLIED";
            else if (requests.values().stream().anyMatch(r -> r.exerciseId.equals(row.exerciseId)&&r.status.equals("PENDING")))reason = "PREDECESSOR_PENDING";
            else if (!ownVersionPath(input.expectedRoutineVersion(), before, requests.values()))reason = "ROUTINE_CHANGED";
            else if (scope.weight(initial.sourcePlacementId()).compareTo(row.previousWeight)!=0)reason = "ROUTINE_CHANGED";
            if (reason!=null) {
                if (resolution)throw failure(RESOLUTION_CONFLICT);
                row.pending(reason);
            }
            else {
                row.status = "APPLIED";
                row.versionBefore = before;
                row.versionAfter = before;
                applied.add(row);
            }
            data.insert(row);
            requests.put(row.id, row);
            if (reason==null)scope.apply(initial.sourcePlacementId(), session.id, row.exerciseId, row.id, InitialPlan.read(session.initialPlan).routineName(), initial.exerciseName(), row.nextWeight, row.occurredAt, clock.instant());
        }
        return applied;
    }

    private static boolean ownVersionPath(long expected, long current, Collection<WeightApprovalRequest> requests) {
        if (expected>current)return false;
        long next = expected;
        var edges = new HashSet<Long>();
        for (var r:requests)if ("APPLIED".equals(r.status)&&r.versionBefore!=null&&r.versionAfter!=null
            &&r.versionBefore<Long.MAX_VALUE&&r.versionAfter==r.versionBefore+1)edges.add(r.versionBefore);
        while (next<current) {
            if (!edges.contains(next))return false;
            next++;
        }
        return true;
    }

    private void completeApprovals(RoutineRepository.ApprovalScope scope, List<WeightApprovalRequest> rows) {
        if (scope==null)return;
        if (scope.changed()&&scope.version()==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);
        scope.complete(clock.instant());
        for (var row:rows)row.versionAfter = scope.version();
    }

    private boolean facts(WorkoutSession session, State state, Map<UUID, SessionExercise> items, Map<UUID, WeightApprovalRequest> approvals) {
        var oldSets = index(data.sets(session.id), s -> s.id);
        var oldCardio = index(data.cardio(session.id), s -> s.id);
        var sets = index(state.performedSets(), SetValue::id);
        var cardio = index(state.cardioResults(), Cardio::id);
        var deleteSets = new HashSet<>(state.deletedPerformedSetIds());
        var deleteCardio = new HashSet<>(state.deletedCardioResultIds());
        if (sets.keySet().stream().anyMatch(deleteSets::contains)||cardio.keySet().stream().anyMatch(deleteCardio::contains))throw failure(INVALID_SNAPSHOT);
        for (var s:oldSets.values())if (s.deletedAt==null&&!sets.containsKey(s.id)&&!deleteSets.contains(s.id))throw failure(SNAPSHOT_INCOMPLETE);
        for (var c:oldCardio.values())if (c.deletedAt==null&&!cardio.containsKey(c.id)&&!deleteCardio.contains(c.id))throw failure(SNAPSHOT_INCOMPLETE);
        var plannedWeights = state.appliedPlan().weightExercises().stream().map(WeightPlan::sessionExerciseId).collect(Collectors.toSet());
        var plannedCardio = index(state.appliedPlan().cardioActivities(), CardioPlan::sessionActivityId);
        // Removal is only legal after explicit cancellation of every existing performance; no implicit deletion.
        if (sets.values().stream().anyMatch(s -> !plannedWeights.contains(s.sessionExerciseId()))||cardio.values().stream().anyMatch(c -> !plannedCardio.containsKey(c.sessionActivityId())))throw failure(PERFORMED_EXERCISE_IN_USE);
        Instant now = clock.instant();
        boolean changed = false;
        for (UUID id:deleteSets) {
            var row = oldSets.get(id);
            if (row==null)throw failure(RECORD_NOT_FOUND);
            if (row.deletedAt==null) {
                audit(session, "SET", id, row.value(), null);
                row.deletedAt = now;
                changed = true;
            }
        }
        for (UUID id:deleteCardio) {
            var row = oldCardio.get(id);
            if (row==null)throw failure(RECORD_NOT_FOUND);
            if (row.deletedAt==null) {
                audit(session, "CARDIO", id, row.value(), null);
                row.deletedAt = now;
                changed = true;
            }
        }
        data.flush();
        // vacate active ordinal/activity uniqueness before inserting replacements
        var ordinals = new HashSet<String>();
        for (var input:state.performedSets()) {
            item(items, input.sessionExerciseId(), "WEIGHT");
            afterStart(session, input.completedAt());
            if (!ordinals.add(input.sessionExerciseId()+":"+input.ordinal()))throw failure(INVALID_SNAPSHOT);
            var row = oldSets.get(input.id());
            if (row!=null) {
                if (row.deletedAt!=null)throw failure(RECORD_DELETED);
                var old = row.value();
                if (!old.sessionExerciseId().equals(input.sessionExerciseId())||old.ordinal()!=input.ordinal()
                    ||!old.completedAt().equals(input.completedAt())||!old.weightContext().equals(input.weightContext()))throw failure(IMMUTABLE_RECORD_CONTEXT);
                if (!old.equals(input)) {
                    audit(session, "SET", row.id, old, input);
                    row.apply(input);
                    changed = true;
                }
            }
            else {
                available("performed_sets", input.id());
                row = new PerformedSet(session.id, input);
                context(session, row, approvals);
                data.insert(row);
                changed = true;
            }
        }
        var activities = new HashSet<UUID>();
        for (var input:state.cardioResults()) {
            item(items, input.sessionActivityId(), "CARDIO");
            afterStart(session, input.occurredAt());
            if (!activities.add(input.sessionActivityId()))throw failure(INVALID_SNAPSHOT);
            if (input.startedAt()!=null) {
                afterStart(session, input.startedAt());
                if (input.occurredAt().isBefore(input.startedAt()))throw failure(CLOCK_CONTEXT_INVALID);
            }
            if (input.result()!=null) {
                if (input.result().timerMs()>Duration.between(input.startedAt(), input.occurredAt()).toMillis())throw failure(CLOCK_CONTEXT_INVALID);
                String kind = plannedCardio.get(input.sessionActivityId()).plan().get("kind").asString();
                if (input.result().distanceKm()!=null&&!Set.of("TREADMILL", "BIKE", "OUTDOOR").contains(kind))throw failure(INVALID_SNAPSHOT);
            }
            var row = oldCardio.get(input.id());
            if (row!=null) {
                if (row.deletedAt!=null)throw failure(RECORD_DELETED);
                var old = row.value();
                if (!old.sessionActivityId().equals(input.sessionActivityId())||!old.status().equals(input.status())||!Objects.equals(old.startedAt(), input.startedAt())
                    ||!old.occurredAt().equals(input.occurredAt())||!Objects.equals(old.result()==null?null:old.result().timerMs(), input.result()==null?null:input.result().timerMs()))throw failure(IMMUTABLE_RECORD_CONTEXT);
                if (!old.equals(input)) {
                    audit(session, "CARDIO", row.id, old, input);
                    row.apply(input);
                    changed = true;
                }
            }
            else {
                available("cardio_results", input.id());
                data.insert(new CardioResult(session.id, input));
                changed = true;
            }
        }
        return changed;
    }

    private void context(WorkoutSession session, PerformedSet set, Map<UUID, WeightApprovalRequest> requests) {
        if (set.contextKind.equals("INITIAL_APPROVED")) {
            var initial = initialWeight(session, set.exerciseId);
            if (initial==null)throw failure(INVALID_SNAPSHOT);
            set.approvalState = "INITIAL_APPROVED";
            set.approvedWeight = new BigDecimal(initial.approvedWeightKg());
        }
        else if (set.contextKind.equals("TODAY_ONLY"))set.approvalState = "TODAY_ONLY";
        else {
            var request = requests.get(set.approvalRequestId);
            if (request==null||!request.exerciseId.equals(set.exerciseId))throw failure(INVALID_SNAPSHOT);
            if (set.completedAt.isBefore(request.occurredAt))throw failure(CLOCK_CONTEXT_INVALID);
            set.approvalState = request.status;
            if (request.status.equals("APPLIED"))set.approvedWeight = request.nextWeight;
        }
    }

    private static void validatePlan(Plan plan, Map<UUID, SessionExercise> items) {
        for (var p:plan.weightExercises())item(items, p.sessionExerciseId(), "WEIGHT");
        for (var p:plan.cardioActivities())item(items, p.sessionActivityId(), "CARDIO");
    }

    private static SessionExercise item(Map<UUID, SessionExercise> items, UUID id, String kind) {
        var item = items.get(id);
        if (item==null||!item.kind.equals(kind))throw failure(INVALID_SNAPSHOT);
        return item;
    }

    private void audit(WorkoutSession s, String kind, UUID id, Object before, Object after) {
        data.insert(new RecordChangeEvent(s.id, kind, id, before, after, clock.instant()));
    }

    private static InitialPlan.Weight initialWeight(WorkoutSession s, UUID id) {
        return InitialPlan.read(s.initialPlan).weightExercises().stream().filter(p -> p.sessionExerciseId().equals(id)).findFirst().orElse(null);
    }

    private void available(String table, UUID id) {
        if (data.occupied(table, id)) {
            throw failure(table.equals("session_weight_approval_requests") ? APPROVAL_REQUEST_ID_CONFLICT : RECORD_ID_CONFLICT);
        }
    }

    private void owner(UUID user, long expected) {
        if (sessions.generation(user, true)!=expected)throw failure(DATA_GENERATION_MISMATCH);
    }

    private WorkoutSession find(UUID user, long generation, UUID id) {
        return sessions.find(user, generation, id, true).orElseThrow(() -> failure(SESSION_NOT_FOUND));
    }

    private static void guard(WorkoutSession s, UUID device, long epoch) {
        if (!s.status.equals("ACTIVE"))throw failure(SESSION_NOT_ACTIVE);
        if (s.writerEpoch!=epoch||!s.editorDeviceId.equals(device))throw failure(EDITOR_OWNERSHIP_LOST);
    }

    private static void incrementable(WorkoutSession s) {
        if (s.version==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);
    }

    private static void afterStart(WorkoutSession s, Instant time) {
        if (time.isBefore(s.startedAt))throw failure(CLOCK_CONTEXT_INVALID);
    }

    private Ack ack(WorkoutSession s, boolean duplicate) {
        return new Ack(s.userId, Long.toString(s.dataGeneration), s.id, Long.toString(s.writerEpoch), Long.toString(s.localRevision), Long.toString(s.version), s.lastSnapshotSavedAt, s.lastWorkoutSyncedAt, duplicate, data.results(s.id, data.approvals(s.id)));
    }

    private Resolution resolution(WorkoutSession s) {
        return new Resolution(s.userId, Long.toString(s.dataGeneration), s.id, Long.toString(s.writerEpoch), Long.toString(s.localRevision), Long.toString(s.version), s.lastWorkoutSyncedAt, data.results(s.id, data.approvals(s.id)));
    }

    private static String hash(Save input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(input).getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T>T transaction(Supplier<T> action) {
        var original = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        try {
            return writes.execute(status -> {
                try {
                    return action.get();
                }
                catch (RuntimeException e) {
                    original.set(e);
                    throw e;
                }
                });
        }
        catch (RuntimeException e) {
            if (original.get()!=null&&original.get()!=e)e.addSuppressed(original.get());
            throw e;
        }
    }

    private static <T>Map<UUID, T> index(List<T> list, Function<T, UUID> key) {
        return list.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static WorkoutSessionFailure failure(WorkoutSessionFailure.Reason reason) {
        return new WorkoutSessionFailure(reason);
    }
}
