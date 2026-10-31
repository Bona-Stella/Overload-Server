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
    private final WorkoutSnapshotService snapshots;
    WorkoutSessionController(WorkoutSessionService service,WorkoutSnapshotService snapshots) { this.service = service; this.snapshots=snapshots; }
    @PutMapping(value="/{id}/snapshot",consumes="application/json")
    ResponseEntity<WorkoutSnapshotData.Ack> snapshot(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id,@RequestBody String body,HttpServletRequest request){
        validate(request,false);return ok(snapshots.save(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutSnapshotRequests.save(body)));
    }
    @PostMapping(value="/{id}/approval-resolutions",consumes="application/json")
    ResponseEntity<WorkoutSnapshotData.Resolution> resolve(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id,@RequestBody String body,HttpServletRequest request){
        validate(request,false);return ok(snapshots.resolve(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutSnapshotRequests.resolve(body)));
    }

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
