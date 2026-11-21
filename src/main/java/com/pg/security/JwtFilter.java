package com.pg.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pg.pg.Pg;
import com.pg.pg.OwnerPgAccess;
import com.pg.pg.PgRepository;
import com.pg.security.JwtService.TokenData;
import com.pg.tenant.TenantContext;
import com.pg.user.Role;
import com.pg.user.Status;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates a Bearer token. An invalid / expired / revoked token simply leaves the request
 * unauthenticated; the security entry point then answers 401 on protected routes (and public routes such as
 * login keep working even if the client sends a stale token).
 *
 * The user and PG are re-checked on every request so that blocking a user or expiring a licence takes effect
 * immediately instead of when the token expires.
 */
public class JwtFilter extends OncePerRequestFilter {

	private static final long CACHE_TTL_MS = 30_000; // 30-second TTL to avoid DB hit per request while keeping status updates instant

	private static class CachedUser {
		final UserEntity user;
		final long timestamp;
		CachedUser(UserEntity user, long timestamp) {
			this.user = user;
			this.timestamp = timestamp;
		}
	}

	private static class CachedPg {
		final Pg pg;
		final long timestamp;
		CachedPg(Pg pg, long timestamp) {
			this.pg = pg;
			this.timestamp = timestamp;
		}
	}

	private final java.util.Map<Long, CachedUser> userCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<Long, CachedPg> pgCache = new java.util.concurrent.ConcurrentHashMap<>();

	private final JwtService jwtService;
	private final UserRepository users;
	private final PgRepository pgs;
	private final OwnerPgAccess access;

	public JwtFilter(JwtService jwtService, UserRepository users, PgRepository pgs, OwnerPgAccess access) {
		this.jwtService = jwtService;
		this.users = users;
		this.pgs = pgs;
		this.access = access;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			String header = request.getHeader("Authorization");
			if (header != null && header.startsWith("Bearer ")) {
				authenticate(header.substring(7));
			}
			chain.doFilter(request, response);
		} finally {
			TenantContext.clear();
			SecurityContextHolder.clearContext();
		}
	}

	private UserEntity findCachedUser(Long userId) {
		if (userId == null) return null;
		long now = System.currentTimeMillis();
		CachedUser cached = userCache.get(userId);
		if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
			return cached.user;
		}
		UserEntity u = users.findById(userId).orElse(null);
		if (u != null) {
			userCache.put(userId, new CachedUser(u, now));
		} else {
			userCache.remove(userId);
		}
		return u;
	}

	private Pg findCachedPg(Long pgId) {
		if (pgId == null) return null;
		long now = System.currentTimeMillis();
		CachedPg cached = pgCache.get(pgId);
		if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
			return cached.pg;
		}
		Pg p = pgs.findById(pgId).orElse(null);
		if (p != null) {
			pgCache.put(pgId, new CachedPg(p, now));
		} else {
			pgCache.remove(pgId);
		}
		return p;
	}

	private void authenticate(String token) {
		TokenData data;
		try {
			data = jwtService.parse(token);
		} catch (JwtException | IllegalArgumentException e) {
			return;
		}
		UserEntity user = findCachedUser(data.userId());
		if (user == null || user.getStatus() != Status.ACTIVE || user.getRole() != data.role()) {
			return;
		}
		if (user.getRole() != Role.SUPER_ADMIN && user.getPgId() != null) {
			// Never trust a pgId from the token over the user's own row. An owner may hold several properties.
			boolean allowed = user.getRole() == Role.OWNER ? access.owns(user, data.pgId())
					: user.getPgId().equals(data.pgId());
			if (!allowed) {
				return;
			}
			Pg pg = findCachedPg(data.pgId());
			if (pg == null || !pg.isLicenseValid()) {
				return;
			}
		}
		TenantContext.set(data.pgId(), data.role().name());
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(),
				null, List.of(new SimpleGrantedAuthority("ROLE_" + data.role().name()))));
	}
}
