package com.overload.overload_server.auth.email;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class EmailIdentityController {
    private final EmailIdentityService identities;
    public EmailIdentityController(EmailIdentityService identities){this.identities=identities;}

    @PostMapping("/api/v1/auth/identities/email/link")
    ResponseEntity<Map<String,Object>> link(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestBody Empty body,HttpServletRequest request){
        rejectQuery(request);
        var result=identities.link(principal.userId(),request);
        return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore())
                .body(Map.of("status",result.created()?"LINKED":"ALREADY_LINKED","identity",Map.of("id",result.identityId(),"provider","email")));
    }

    @PostMapping("/api/v1/auth/identities/email/unlink")
    ResponseEntity<Void> unlink(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestBody Unlink body,HttpServletRequest request){
        rejectQuery(request);
        identities.unlink(principal.userId(),body.identityId(),request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    public record Empty(){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unexpected(String field,Object value){throw new IllegalArgumentException("Unexpected field");}
    }
    public record Unlink(UUID identityId){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unexpected(String field,Object value){throw new IllegalArgumentException("Unexpected field");}
        @Override public String toString(){return "EmailUnlink[redacted]";}
    }
    private static void rejectQuery(HttpServletRequest request){
        if(request.getParameterMap().keySet().stream().anyMatch(key->!key.equals("_csrf"))){throw new OtpFailure(400,"INVALID_REQUEST");}
    }
}
