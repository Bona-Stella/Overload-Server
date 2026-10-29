package com.overload.overload_server.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTests {

	private final RequestIdFilter filter = new RequestIdFilter();
	private final MockHttpServletRequest request = new MockHttpServletRequest();
	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
		@Override protected void append(ILoggingEvent event) {
			event.prepareForDeferredProcessing();
			super.append(event);
		}
	};

	@BeforeEach
	void capture() {
		MDC.clear();
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void cleanup() {
		logger.detachAppender(logs);
		logs.stop();
		MDC.clear();
	}

	@Test
	void generatesServerUuidAndNeverReflectsClientId() throws Exception {
		request.addHeader(RequestIdFilter.HEADER, "untrusted-client-id");
		filter.doFilter(request, response, (incoming, outgoing) -> {
			assertThat(incoming.getAttribute(RequestIdFilter.ATTRIBUTE)).isInstanceOf(UUID.class);
			assertThat(MDC.get("requestId")).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
			assertThat(UUID.fromString(MDC.get("requestId")).version()).isEqualTo(4);
		});
		assertThat(MDC.get("requestId")).isNull();
		assertThat(response.getHeaders(RequestIdFilter.HEADER)).hasSize(1).doesNotContain("untrusted-client-id");
		assertThat(logs.list).hasSize(1);
		assertThat(logs.list.getFirst().getMDCPropertyMap().get("requestId")).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
	}

	@Test
	void sequentialRequestsHaveDifferentIdsAndNoMdcLeak() throws Exception {
		filter.doFilter(request, response, (incoming, outgoing) -> {});
		var next = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest(), next, (incoming, outgoing) ->
				assertThat(MDC.get("requestId")).isEqualTo(next.getHeader(RequestIdFilter.HEADER)));
		assertThat(next.getHeader(RequestIdFilter.HEADER)).isNotEqualTo(response.getHeader(RequestIdFilter.HEADER));
		assertThat(MDC.get("requestId")).isNull();
	}

	@Test
	void overlappingThreadsKeepTheirOwnMdcAndClearItAfterward() throws Exception {
		var entered = new java.util.concurrent.CountDownLatch(2);
		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<String> action = () -> {
				var ownResponse = new MockHttpServletResponse();
				filter.doFilter(new MockHttpServletRequest(), ownResponse, (incoming, outgoing) -> {
					entered.countDown();
					try { assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
					catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ServletException(interrupted); }
					assertThat(MDC.get("requestId")).isEqualTo(ownResponse.getHeader(RequestIdFilter.HEADER));
				});
				assertThat(MDC.get("requestId")).isNull();
				return ownResponse.getHeader(RequestIdFilter.HEADER);
			};
			var first = executor.submit(action);
			var second = executor.submit(action);
			assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNotEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
		}
	}

	@Test
	void restoresOuterMdcEvenWhenDownstreamThrows() {
		MDC.put("requestId", "outer-scope");
		MDC.put("other", "preserved");
		assertThatThrownBy(() -> filter.doFilter(request, response, (incoming, outgoing) -> {
			throw new ServletException("synthetic-sensitive-exception");
		})).isInstanceOf(ServletException.class);
		assertThat(MDC.get("requestId")).isEqualTo("outer-scope");
		assertThat(MDC.get("other")).isEqualTo("preserved");
		assertThat(logs.list.getFirst().getFormattedMessage()).contains("outcome=THREW").doesNotContain("synthetic-sensitive-exception");
		assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
	}

	@Test
	void errorRedispatchReusesAttributeAfterOriginalFilterUnwinds() throws Exception {
		filter.doFilter(request, response, (incoming, outgoing) -> {});
		var id = response.getHeader(RequestIdFilter.HEADER);
		request.setDispatcherType(DispatcherType.ERROR);
		request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/synthetic-secret");
		response.reset();
		filter.doFilter(request, response, (incoming, outgoing) -> assertThat(MDC.get("requestId")).isEqualTo(id));
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
		assertThat(logs.list).hasSize(2);
		assertThat(MDC.get("requestId")).isNull();
	}

	@Test
	void nestedErrorDispatchRewrapsOriginalResponseAndRestoresOuterContext() throws Exception {
		filter.doFilter(request, response, (incoming, outgoing) -> {
			var id = MDC.get("requestId");
			request.setDispatcherType(DispatcherType.ERROR);
			request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/synthetic-secret");
			response.reset();
			filter.doFilter(request, response, (errorRequest, errorResponse) -> {
				assertThat(((HttpServletResponse) errorResponse).getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
				assertThat(MDC.get("requestId")).isEqualTo(id);
			});
			assertThat(MDC.get("requestId")).isEqualTo(id);
		});
		assertThat(MDC.get("requestId")).isNull();
	}

	@Test
	void duplicateInvocationWithinDispatchDoesNotRunCorrelationTwice() throws Exception {
		filter.doFilter(request, response, (incoming, outgoing) ->
				filter.doFilter(incoming, outgoing, (duplicateRequest, duplicateResponse) -> {}));
		assertThat(logs.list).hasSize(1);
	}

	@Test
	void resetAndResetBufferPreserveSingleIdWithoutChangingOtherHeadersOrBody() throws Exception {
		filter.doFilter(request, response, (incoming, outgoing) -> {
			var http = (HttpServletResponse) outgoing;
			var id = http.getHeader(RequestIdFilter.HEADER);
			http.setHeader("Discarded", "before-reset");
			http.reset();
			assertThat(http.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
			assertThat(http.getHeader("Discarded")).isNull();
			http.setHeader("x-request-id", "untrusted-replacement");
			http.addHeader("X-Request-ID", "duplicate");
			http.setIntHeader("X-Request-ID", 1);
			http.addIntHeader("X-Request-ID", 2);
			http.setHeader("Cache-Control", "no-store");
			http.getWriter().write("discarded");
			http.resetBuffer();
			http.getWriter().write("preserved");
			assertThat(http.getHeaders(RequestIdFilter.HEADER)).containsExactly(id);
		});
		assertThat(response.getContentAsString()).isEqualTo("preserved");
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
	}

	@Test
	void headerIsPresentBeforeCommitAndResetAfterCommitKeepsServletSemantics() throws Exception {
		filter.doFilter(request, response, (incoming, outgoing) -> {
			var http = (HttpServletResponse) outgoing;
			http.setStatus(204);
			http.flushBuffer();
			assertThat(http.isCommitted()).isTrue();
			assertThat(http.getHeader(RequestIdFilter.HEADER)).isNotBlank();
			assertThatThrownBy(http::reset).isInstanceOf(IllegalStateException.class);
		});
		assertThat(response.getStatus()).isEqualTo(204);
		assertThat(response.getContentAsByteArray()).isEmpty();
	}

	@Test
	void logsAndResponseNeverCopySensitiveInputOrExceptionDetails() throws Exception {
		var secret = "synthetic-sensitive-marker";
		request.setRequestURI("/" + secret);
		request.setQueryString("code=" + secret);
		request.setContent(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		for (var name : new String[] {"Cookie", "X-CSRF-TOKEN", "Authorization", "X-Request-ID"}) {
			request.addHeader(name, secret);
		}
		filter.doFilter(request, response, (incoming, outgoing) -> {});
		assertThat(response.getContentAsByteArray()).isEmpty();
		assertThat(response.getHeader(RequestIdFilter.HEADER)).doesNotContain(secret);
		assertThat(logs.list).hasSize(1);
		assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain(secret, "code=", "Cookie", "Authorization");
		assertThat(logs.list.getFirst().getArgumentArray()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(secret));
	}
}
