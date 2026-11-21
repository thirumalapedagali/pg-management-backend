package com.pg.common;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pg.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filter that tracks API response execution duration (in milliseconds) and logs request metadata
 * (HTTP Method, URI, Status Code, User, Tenant PG ID, Execution Time) for performance tracking and debugging.
 */
public class ApiLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ApiLoggingFilter.class);
	private static final long SLOW_THRESHOLD_MS = 1000L;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long start = System.currentTimeMillis();
		String reqId = UUID.randomUUID().toString().substring(0, 8);

		try {
			chain.doFilter(request, response);
		} finally {
			long duration = System.currentTimeMillis() - start;
			int status = response.getStatus();
			String method = request.getMethod();
			String uri = request.getRequestURI();
			String queryString = request.getQueryString();
			String fullPath = queryString != null ? uri + "?" + queryString : uri;

			String user = "anonymous";
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
				user = auth.getName();
			}

			Long pgId = TenantContext.getPgId();
			String pgStr = pgId != null ? String.valueOf(pgId) : "-";

			MDC.put("req", reqId);
			MDC.put("user", user);
			MDC.put("pg", pgStr);

			if (duration > SLOW_THRESHOLD_MS) {
				log.warn("SLOW API {} {} [{}] {}ms | user={} | pg={}", method, fullPath, status, duration, user, pgStr);
			} else if (status >= 500) {
				log.error("API SERVER ERROR {} {} [{}] {}ms | user={} | pg={}", method, fullPath, status, duration, user, pgStr);
			} else if (status >= 400) {
				log.info("API CLIENT ERROR {} {} [{}] {}ms | user={} | pg={}", method, fullPath, status, duration, user, pgStr);
			} else {
				log.info("API {} {} [{}] {}ms | user={} | pg={}", method, fullPath, status, duration, user, pgStr);
			}

			MDC.clear();
		}
	}
}
