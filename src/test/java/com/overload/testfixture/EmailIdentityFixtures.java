package com.overload.testfixture;

import com.overload.overload_server.security.SessionPrincipal;
import com.overload.overload_server.security.SessionReauthentication;
import com.overload.overload_server.security.SessionPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

/** Explicit import only. Exercises the real JDBC rotation without inventing an email verification. */
@TestConfiguration(proxyBeanMethods=false)
public class EmailIdentityFixtures {
    @RestController
    public static class RotationProbe {
        private final SessionReauthentication sessions;
        private final SessionPolicy policy;
        private final TransactionTemplate transaction;
        private final com.overload.overload_server.security.AuthenticationSessionService base;
        public RotationProbe(SessionReauthentication sessions,SessionPolicy policy,PlatformTransactionManager manager,
                com.overload.overload_server.security.AuthenticationSessionService base){
            this.sessions=sessions;this.policy=policy;this.transaction=new TransactionTemplate(manager);
            this.base=base;
        }
        @PostMapping("/__email_identity_test__/rotate")
        ResponseEntity<Void> rotate(@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest request,HttpServletResponse response){
            var snapshot=sessions.capture(request,principal.userId());
            var rotation=transaction.execute(status->sessions.rotate(snapshot,policy.now()));
            sessions.complete(request,response,rotation);
            return ResponseEntity.noContent().build();
        }
        @PostMapping("/__email_identity_test__/base-rotate")
        ResponseEntity<Void> baseRotate(@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest request,HttpServletResponse response){
            base.reauthenticateAfterVerification(request,response,principal.userId());
            return ResponseEntity.noContent().build();
        }
    }
}
