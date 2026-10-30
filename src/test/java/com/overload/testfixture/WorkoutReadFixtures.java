package com.overload.testfixture;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.function.Consumer;

/** Observes executed JDBC (including Hibernate) without substituting query results or transaction managers. */
@TestConfiguration(proxyBeanMethods = false)
public class WorkoutReadFixtures {

	public record Execution(String sql, Connection connection, boolean before) { }
	public static volatile Consumer<Execution> observer;

	@Bean
	static BeanPostProcessor observeSql() {
		return new BeanPostProcessor() {
			@Override public Object postProcessAfterInitialization(Object bean, String name) {
				if (!(bean instanceof DataSource source)) return bean;
				return new DelegatingDataSource(source) {
					@Override public Connection getConnection() throws java.sql.SQLException { return observe(super.getConnection()); }
					@Override public Connection getConnection(String user, String password) throws java.sql.SQLException {
						return observe(super.getConnection(user, password));
					}
				};
			}
		};
	}

	private static Connection observe(Connection connection) {
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
				(proxy, method, args) -> {
					var result = invoke(connection, method, args);
					if (result instanceof Statement statement && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
						String preparedSql = args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
						var api = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
						return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (ignored, call, values) -> {
							boolean execute = call.getName().startsWith("execute");
							String sql = preparedSql != null ? preparedSql
									: values != null && values.length > 0 && values[0] instanceof String text ? text : "<batch>";
							var callback = observer;
							if (execute && callback != null) callback.accept(new Execution(sql, connection, true));
							var value = invoke(statement, call, values);
							if (execute && callback != null) callback.accept(new Execution(sql, connection, false));
							return value;
						});
					}
					return result;
				});
	}

	private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
		try { return method.invoke(target, args); }
		catch (InvocationTargetException exception) { throw exception.getCause(); }
	}
}
