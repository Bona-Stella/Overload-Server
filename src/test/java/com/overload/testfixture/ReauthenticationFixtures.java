package com.overload.testfixture;

import com.overload.overload_server.security.ReauthenticationSessionRepository;
import com.overload.overload_server.security.SessionReauthentication;
import com.overload.overload_server.security.SessionPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Faults are installed only by explicit test import. No production authentication bypass. */
@TestConfiguration(proxyBeanMethods = false)
public class ReauthenticationFixtures {
	public static volatile Consumer<SessionReauthentication.Snapshot> beforeValidate;
	public static volatile Consumer<SessionReauthentication.Snapshot> beforeRotate;
	public static volatile Consumer<SessionReauthentication.Rotation> afterRotate;
	public static volatile Consumer<Session> afterRead;

	public static void reset() { beforeValidate = null; beforeRotate = null; afterRotate = null; afterRead = null; }

	@Bean
	static BeanPostProcessor reauthenticationFaults() {
		return new BeanPostProcessor() {
			@Override public Object postProcessAfterInitialization(Object bean, String name) {
				if (bean instanceof ReauthenticationSessionRepository repository) {
					ReauthenticationSessionRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
					var wrapped = spy(target);
					doAnswer(call -> { var hook = beforeValidate; if (hook != null) { hook.accept(call.getArgument(0)); } return call.callRealMethod(); })
							.when(wrapped).validate(any());
					doAnswer(call -> {
						var before = beforeRotate; if (before != null) { before.accept(call.getArgument(0)); }
						var result = (SessionReauthentication.Rotation) call.callRealMethod();
						var after = afterRotate; if (after != null) { after.accept(result); } return result;
					}).when(wrapped).rotate(any());
					return wrapped;
				}
				if (bean instanceof JdbcIndexedSessionRepository repository) {
					var wrapped = spy(repository);
					doAnswer(call -> {
						var result = (Session) call.callRealMethod();
						var hook = afterRead; if (hook != null && result != null) { hook.accept(result); } return result;
					}).when(wrapped).findById(anyString());
					return wrapped;
				}
				return bean;
			}
		};
	}

	@RestController
	public static class RecentProbe {
		private final SessionPolicy policy;
		public RecentProbe(SessionPolicy policy) { this.policy = policy; }
		@GetMapping("/__reauth_test__/recent")
		ResponseEntity<Void> recent(HttpServletRequest request) {
			return ResponseEntity.status(policy.hasRecentReauthentication(request.getSession(false)) ? 204 : 403).build();
		}
	}
}
