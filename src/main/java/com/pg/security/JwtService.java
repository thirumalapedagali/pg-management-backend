package com.pg.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pg.user.Role;
import com.pg.user.UserEntity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/** Issues and verifies access tokens. The signing secret comes from configuration only. */
@Service
public class JwtService {

	private final SecretKey key;
	private final Duration expiry;
	private final Duration refreshGrace;

	public JwtService(@Value("${app.jwt.secret:}") String secret, @Value("${app.jwt.expiry-hours:24}") long expiryHours,
			@Value("${app.jwt.refresh-grace-days:7}") long graceDays) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException(
					"app.jwt.secret (env JWT_SECRET) must be set and at least 32 characters long");
		}
		this.key = Keys.hmacShaKeyFor(bytes);
		this.expiry = Duration.ofHours(expiryHours);
		this.refreshGrace = Duration.ofDays(graceDays);
	}

	/**
	 * @param pgId the PG this token is scoped to. For non-super-admins it must be the user's own PG; a
	 *             SUPER_ADMIN may pass any PG or null (platform-level token).
	 */
	public String issue(UserEntity user, Long pgId) {
		Date now = new Date();
		var builder = Jwts.builder().setSubject(user.getUsername()).claim("uid", user.getId())
				.claim("role", user.getRole().name()).setIssuedAt(now)
				.setExpiration(new Date(now.getTime() + expiry.toMillis()));
		if (pgId != null) {
			builder.claim("pgId", pgId);
		}
		return builder.signWith(key, SignatureAlgorithm.HS256).compact();
	}

	/** Strictly valid (signature + not expired) token. Throws {@link JwtException} otherwise. */
	public TokenData parse(String token) {
		return toData(Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody());
	}

	/**
	 * Like {@link #parse} but also accepts a token that expired less than the refresh grace period ago.
	 * Signature is always verified. Throws {@link JwtException} when too old or invalid.
	 */
	public TokenData parseForRefresh(String token) {
		Claims claims;
		try {
			claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
		} catch (ExpiredJwtException e) {
			claims = e.getClaims();
			long expiredAt = claims.getExpiration().getTime();
			if (System.currentTimeMillis() - expiredAt > refreshGrace.toMillis()) {
				throw e;
			}
		}
		return toData(claims);
	}

	private TokenData toData(Claims c) {
		Number uid = c.get("uid", Number.class);
		Number pg = c.get("pgId", Number.class);
		return new TokenData(uid == null ? null : uid.longValue(), c.getSubject(), pg == null ? null : pg.longValue(),
				Role.valueOf(c.get("role", String.class)));
	}

	public record TokenData(Long userId, String username, Long pgId, Role role) {
	}
}
