package com.overload.overload_server.routine;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/routines")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RoutineController {
	private final RoutineService service;
	RoutineController(RoutineService service) { this.service = service; }
	@GetMapping
	ResponseEntity<RoutineResponses.Listing> list(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
		validate(request, true); return ok(service.list(principal.userId()));
	}
	@GetMapping("/{id}")
	ResponseEntity<RoutineResponses.Detail> detail(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id, HttpServletRequest request) {
		validate(request, true); return ok(service.detail(principal.userId(), RoutineRequests.uuid(id), false));
	}
	@GetMapping("/{id}/removed-placements")
	ResponseEntity<RoutineResponses.Detail> removed(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id, HttpServletRequest request) {
		validate(request, true); return ok(service.detail(principal.userId(), RoutineRequests.uuid(id), true));
	}
	@PostMapping(consumes = "application/json")
	ResponseEntity<RoutineResponses.Detail> create(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody String body, HttpServletRequest request) {
		validate(request, false); var result = service.create(principal.userId(), RoutineRequests.create(body));
		return ResponseEntity.created(URI.create("/api/v1/routines/" + result.id())).cacheControl(CacheControl.noStore()).body(result);
	}
	@PutMapping(value = "/{id}", consumes = "application/json")
	ResponseEntity<RoutineResponses.Detail> update(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id,
			@RequestBody String body, HttpServletRequest request) {
		validate(request, false); return ok(service.update(principal.userId(), RoutineRequests.uuid(id), RoutineRequests.update(body)));
	}
	@DeleteMapping(value = "/{id}", consumes = "application/json")
	ResponseEntity<Void> delete(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id,
			@RequestBody String body, HttpServletRequest request) {
		validate(request, false); service.delete(principal.userId(), RoutineRequests.uuid(id), RoutineRequests.delete(body));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
	private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
	private static void validate(HttpServletRequest request, boolean read) {
		if ((request.getQueryString() != null && !request.getQueryString().isEmpty()) || read &&
				(request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) throw RoutineRequests.invalid();
	}
}
