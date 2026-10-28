package com.overload.testfixture;

import com.overload.overload_server.security.ReauthenticationSessionRepository;
import com.overload.overload_server.security.SessionReauthentication;
import com.overload.overload_server.security.SessionPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@TestConfiguration(proxyBeanMethods = false)
public class GoogleReauthenticationFixtures {
	public static volatile Consumer<SessionReauthentication.Snapshot> beforeRotate;
	public static volatile Consumer<SessionReauthentication.Rotation> afterRotate;
	public static void reset() { beforeRotate = null; afterRotate = null; GoogleOidcFixtures.onSessionRead = null; }
	@Bean static BeanPostProcessor googleReauthenticationFaults() {
		return new BeanPostProcessor() {
			@Override public Object postProcessAfterInitialization(Object bean, String name) {
				if (!(bean instanceof ReauthenticationSessionRepository repository)) { return bean; }
				ReauthenticationSessionRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
				var wrapped = spy(target);
				doAnswer(call -> {
					var before = beforeRotate; if (before != null) { before.accept(call.getArgument(0)); }
					var result = (SessionReauthentication.Rotation) call.callRealMethod();
					var after = afterRotate; if (after != null) { after.accept(result); } return result;
				}).when(wrapped).rotate(any(), any());
				return wrapped;
			}
		};
	}
	@RestController public static class Recent {
		private final SessionPolicy policy;
		public Recent(SessionPolicy policy) { this.policy = policy; }
		@GetMapping("/__google_reauth_test__/recent")
		ResponseEntity<Void> recent(HttpServletRequest request) {
			return ResponseEntity.status(policy.hasRecentReauthentication(request.getSession(false)) ? 204 : 403).build();
		}
	}
}
