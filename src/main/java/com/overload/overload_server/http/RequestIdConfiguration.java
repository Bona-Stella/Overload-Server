package com.overload.overload_server.http;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.SessionRepositoryFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdConfiguration {

	@Bean
	FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
		// The filter is owned only by this registration, not also a component/Filter bean or Security filter.
		var registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setName("requestIdFilter");
		registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
		registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
		registration.addUrlPatterns("/*");
		return registration;
	}
}
