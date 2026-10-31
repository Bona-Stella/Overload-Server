package com.overload.overload_server.workoutsession;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/workout-sessions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkoutSessionController {
    private final WorkoutSessionService service;
    WorkoutSessionController(WorkoutSessionService service) { this.service = service; }

    @PostMapping(consumes = "application/json")
    ResponseEntity<WorkoutSessionResponses.Detail> start(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestBody String body, HttpServletRequest request) {
        validate(request, false);
        var result = service.start(principal.userId(), WorkoutSessionRequests.start(body));
        return result.created() ? ResponseEntity.created(URI.create("/api/v1/workout-sessions/" + result.session().id()))
                .cacheControl(CacheControl.noStore()).body(result.session()) : ok(result.session());
    }
    @GetMapping("/active")
    ResponseEntity<WorkoutSessionResponses.Active> active(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        validate(request, true); return ok(service.active(principal.userId()));
    }
    @GetMapping("/{id}")
    ResponseEntity<WorkoutSessionResponses.Detail> detail(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id, HttpServletRequest request) {
        validate(request, true); return ok(service.detail(principal.userId(), WorkoutSessionRequests.uuid(id)));
    }
    @PostMapping(value = "/{id}/takeover", consumes = "application/json")
    ResponseEntity<WorkoutSessionResponses.Detail> takeover(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
        validate(request, false);
        return ok(service.takeover(principal.userId(), WorkoutSessionRequests.uuid(id), WorkoutSessionRequests.takeover(body)));
    }
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private static void validate(HttpServletRequest request, boolean read) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()
                || read && (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) throw WorkoutSessionRequests.invalid();
    }
}
