package com.overload.overload_server.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Correlates servlet dispatches without reading credentials, URLs or response bodies. */
public final class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-ID";
	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
	public static final String MDC_KEY = "requestId";
	private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// Some containers start ERROR before the original REQUEST filter has unwound.
		doFilterInternal(request, response, chain);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var id = request.getAttribute(ATTRIBUTE) instanceof UUID existing ? existing : UUID.randomUUID();
		request.setAttribute(ATTRIBUTE, id);
		var previous = MDC.get(MDC_KEY);
		MDC.put(MDC_KEY, id.toString());
		long started = System.nanoTime();
		boolean returned = false;
		try {
			chain.doFilter(request, new IdentifiedResponse(response, id.toString()));
			returned = true;
		} finally {
			try {
				// Status is observed at this dispatch boundary, not a claim about a later ERROR response or DB commit.
				log.info("HTTP dispatch ended requestId={} dispatcher={} status={} outcome={} durationMs={}",
						id, request.getDispatcherType(), response.getStatus(), returned ? "RETURNED" : "THREW",
						(System.nanoTime() - started) / 1_000_000);
			} finally {
				if (previous == null) {
					MDC.remove(MDC_KEY);
				} else {
					MDC.put(MDC_KEY, previous);
				}
			}
		}
	}

	private static final class IdentifiedResponse extends HttpServletResponseWrapper {
		private final String id;

		IdentifiedResponse(HttpServletResponse response, String id) {
			super(response);
			this.id = id;
			super.setHeader(HEADER, id);
		}

		@Override
		public void reset() {
			super.reset();
			super.setHeader(HEADER, id);
		}

		@Override
		public void setHeader(String name, String value) {
			super.setHeader(name, HEADER.equalsIgnoreCase(name) ? id : value);
		}

		@Override
		public void addHeader(String name, String value) {
			if (HEADER.equalsIgnoreCase(name)) {
				super.setHeader(HEADER, id);
			} else {
				super.addHeader(name, value);
			}
		}

		@Override
		public void setIntHeader(String name, int value) {
			if (HEADER.equalsIgnoreCase(name)) {
				super.setHeader(HEADER, id);
			} else {
				super.setIntHeader(name, value);
			}
		}

		@Override
		public void addIntHeader(String name, int value) {
			if (HEADER.equalsIgnoreCase(name)) {
				super.setHeader(HEADER, id);
			} else {
				super.addIntHeader(name, value);
			}
		}
	}
}
