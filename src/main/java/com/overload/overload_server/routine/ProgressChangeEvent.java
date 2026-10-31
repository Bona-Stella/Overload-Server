package com.overload.overload_server.routine;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only domain fact. There is no merge, update, removal or generic event payload. */
@Entity
@Table(name = "progress_change_events")
@org.hibernate.annotations.Immutable
public class ProgressChangeEvent {
	@Id UUID id;
	@Column(name = "user_id", nullable = false, updatable = false) UUID userId;
	@Column(name = "data_generation", nullable = false, updatable = false) long dataGeneration;
	@Column(name = "routine_id", nullable = false, updatable = false) UUID routineId;
	@Column(name = "placement_id", nullable = false, updatable = false) UUID placementId;
	@Column(name = "routine_name", nullable = false, updatable = false, columnDefinition = "text") String routineName;
	@Column(name = "exercise_name", nullable = false, updatable = false, columnDefinition = "text") String exerciseName;
	@Column(name = "previous_weight", nullable = false, updatable = false, precision = 20, scale = 6) BigDecimal previousWeight;
	@Column(name = "next_weight", nullable = false, updatable = false, precision = 20, scale = 6) BigDecimal nextWeight;
	@Column(name = "occurred_at", nullable = false, updatable = false) Instant occurredAt;
	@Column(name = "recorded_at", nullable = false, updatable = false) Instant recordedAt;
	@Column(nullable = false, updatable = false, columnDefinition = "text") String source;
	@Column(name="session_id",updatable=false) UUID sessionId;
	@Column(name="session_exercise_id",updatable=false) UUID sessionExerciseId;
	@Column(name="approval_request_id",updatable=false) UUID approvalRequestId;
	protected ProgressChangeEvent() { }
	static ProgressChangeEvent workout(Routine routine,RoutineWeightPlacement placement,UUID session,UUID exercise,UUID request,
			String routineName,String exerciseName,BigDecimal next,Instant occurred,Instant now) {
		var value=new ProgressChangeEvent(); value.id=UUID.randomUUID(); value.userId=routine.userId; value.dataGeneration=routine.dataGeneration;
		value.routineId=routine.id; value.placementId=placement.id; value.routineName=routineName; value.exerciseName=exerciseName;
		value.previousWeight=placement.approvedWeight; value.nextWeight=next; value.occurredAt=occurred; value.recordedAt=now;
		value.source="WORKOUT"; value.sessionId=session; value.sessionExerciseId=exercise; value.approvalRequestId=request; return value;
	}

	static ProgressChangeEvent create(Routine routine, RoutineWeightPlacement placement, String exerciseName,
			RoutineRequests.Approval approval, Instant now) {
		var value = new ProgressChangeEvent();
		value.id = UUID.randomUUID(); value.userId = routine.userId; value.dataGeneration = routine.dataGeneration;
		value.routineId = routine.id; value.placementId = placement.id; value.routineName = routine.name;
		value.exerciseName = exerciseName; value.previousWeight = placement.approvedWeight;
		value.nextWeight = approval.nextApprovedWeightKg(); value.occurredAt = approval.occurredAt();
		value.recordedAt = now; value.source = "ROUTINE_EDITOR";
		return value;
	}
}
