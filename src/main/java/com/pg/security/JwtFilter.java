package com.pg.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pg.pg.Pg;
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

	private final JwtService jwtService;
	private final UserRepository users;
	private final PgRepository pgs;

	public JwtFilter(JwtService jwtService, UserRepository users, PgRepository pgs) {
		this.jwtService = jwtService;
		this.users = users;
		this.pgs = pgs;
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

	private void authenticate(String token) {
		TokenData data;
		try {
			data = jwtService.parse(token);
		} catch (JwtException | IllegalArgumentException e) {
			return;
		}
		UserEntity user = data.userId() == null ? null : users.findById(data.userId()).orElse(null);
		if (user == null || user.getStatus() != Status.ACTIVE || user.getRole() != data.role()) {
			return;
		}
		if (user.getRole() != Role.SUPER_ADMIN && user.getPgId() != null) {
			// Never trust a pgId from the token over the user's own row.
			if (!user.getPgId().equals(data.pgId())) {
				return;
			}
			Pg pg = pgs.findById(user.getPgId()).orElse(null);
			if (pg == null || !pg.isLicenseValid()) {
				return;
			}
		}
		TenantContext.set(data.pgId(), data.role().name());
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(),
				null, List.of(new SimpleGrantedAuthority("ROLE_" + data.role().name()))));
	}
}
