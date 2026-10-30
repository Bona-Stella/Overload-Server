package com.overload.testfixture;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Consumer;

/** Observes real transactions. Imported only by the disposable compatibility test context. */
@TestConfiguration(proxyBeanMethods = false)
public class JpaCompatibilityFixtures {

	public static volatile Consumer<SessionTransaction> sessionObserver;
	public static volatile Consumer<Boolean> jpaObserver;
	public record SessionTransaction(int backendPid, String requestId, boolean jpaBound,
			boolean readOnly, TransactionTemplate original) { }

	@Bean
	HibernatePropertiesCustomizer observeJpaTransactions(ConfigurableListableBeanFactory beans) {
		return properties -> properties.put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
			var observer = jpaObserver;
			if (observer != null) {
				observer.accept(TransactionSynchronizationManager.isActualTransactionActive()
						&& TransactionSynchronizationManager.hasResource(beans.getBean(EntityManagerFactory.class)));
			}
			return sql;
		});
	}

	@Bean
	static BeanPostProcessor observeSessionTransactions(ConfigurableListableBeanFactory beans) {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String name) {
				if (bean instanceof JdbcIndexedSessionRepository repository) {
					var original = (TransactionTemplate) ReflectionTestUtils.getField(repository, "transactionOperations");
					ReflectionTestUtils.setField(repository, "transactionOperations", new TransactionOperations() {
						@Override
						public <T> T execute(TransactionCallback<T> callback) {
							return original.execute(status -> {
								var observer = sessionObserver;
								if (observer != null) {
									var jdbc = new JdbcTemplate(beans.getBean(DataSource.class));
									observer.accept(new SessionTransaction(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
											MDC.get("requestId"), TransactionSynchronizationManager.hasResource(beans.getBean(EntityManagerFactory.class)),
											TransactionSynchronizationManager.isCurrentTransactionReadOnly(), original));
								}
								return callback.doInTransaction(status);
							});
						}
					});
				}
				return bean;
			}
		};
	}
}
