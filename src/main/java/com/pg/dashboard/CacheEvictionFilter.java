package com.pg.dashboard;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import com.pg.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Drops the cached dashboard numbers after any request that may have changed data. It is registered in the security chain right after {@code JwtFilter}, so the request's tenant context is still set when the request has been handled.
 */
public class CacheEvictionFilter extends OncePerRequestFilter {

	private final DashboardCache cache;

	public CacheEvictionFilter(DashboardCache cache) {
		this.cache = cache;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		} finally {
			String m = request.getMethod();
			if (!("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m))) {
				Long pgId = TenantContext.getPgId();
				if (pgId == null) {
					// Admin changes (licence, status, ...) and registration (which can add a property to an existing
					// owner) can affect any property; logins etc. affect none.
					String uri = request.getRequestURI();
					if (uri.startsWith("/api/admin") || uri.startsWith("/api/public/owner-registrations")) {
						cache.evictAll();
					} else if (uri.startsWith("/api/pg/properties")) {
						// A new property appears in the public list.
						cache.evictPublic();
					}
				} else {
					cache.evictPg(pgId);
					cache.evictOwned();
					// Vacancies, rent, pictures and the profile all show in the public property list.
					cache.evictPublic();
					// Rooms / beds only change when a room is edited or a resident is added, moved, vacated,
					// rejoined, removed or approved from a booking request - not for payments or expenses.
					String uri = request.getRequestURI();
					if (uri.startsWith("/api/rooms") || uri.startsWith("/api/residents")
							|| uri.startsWith("/api/booking-requests") || uri.startsWith("/api/me")) {
						cache.evictRooms(pgId);
					}
				}
			}
		}
	}
}
