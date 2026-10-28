package com.overload.overload_server.auth.identity;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class GoogleIdentityController {
    private final GoogleIdentityService identities;
    public GoogleIdentityController(GoogleIdentityService identities){this.identities=identities;}
    @PostMapping("/api/v1/auth/identities/google/unlink")
    ResponseEntity<Void> unlink(@AuthenticationPrincipal SessionPrincipal principal,@RequestBody Unlink body,HttpServletRequest request){
        if(request.getParameterMap().keySet().stream().anyMatch(k->!k.equals("_csrf"))){throw new IdentityChangeFailure(400,"INVALID_REQUEST");}
        identities.unlink(principal.userId(),body.identityId(),request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    public record Unlink(UUID identityId){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unexpected(String key,Object value){throw new IllegalArgumentException("Unexpected field");}
        @Override public String toString(){return "GoogleUnlink[redacted]";}
    }
}
