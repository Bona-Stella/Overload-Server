package com.overload.overload_server.workout;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkoutWriteController {

	private final WorkoutWriteService service;
	WorkoutWriteController(WorkoutWriteService service) { this.service = service; }

	@PostMapping("/api/v1/exercises")
	ResponseEntity<ExerciseWriteResponse> create(@AuthenticationPrincipal SessionPrincipal principal,
			@RequestBody WorkoutWriteRequests.CreateExercise body, HttpServletRequest request) {
		validate(request);
		var result = service.create(principal.userId(), body);
		// Location is the resource's PATCH URI; there is no new individual GET endpoint.
		return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
				.location(URI.create("/api/v1/exercises/" + result.exercise().id())).body(result.exercise());
	}

	@PatchMapping("/api/v1/exercises/{id}")
	ResponseEntity<ExerciseWriteResponse> rename(@AuthenticationPrincipal SessionPrincipal principal,
			@PathVariable UUID id, @RequestBody WorkoutWriteRequests.RenameExercise body, HttpServletRequest request) {
		validate(request);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.rename(principal.userId(), id, body));
	}

	@PatchMapping("/api/v1/workout-settings")
	ResponseEntity<WorkoutSettingsResponse> settings(@AuthenticationPrincipal SessionPrincipal principal,
			@RequestBody WorkoutWriteRequests.ChangeRest body, HttpServletRequest request) {
		validate(request);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.changeRest(principal.userId(), body));
	}

	private static void validate(HttpServletRequest request) {
		if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
			throw new WorkoutWriteFailure(WorkoutWriteFailure.Reason.INVALID_REQUEST);
		}
	}
}
