package com.pg.auth;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.BadRequestException;
import com.pg.common.ForbiddenException;
import com.pg.common.LicenseExpiredException;
import com.pg.common.NotFoundException;
import com.pg.common.UnauthorizedException;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.security.JwtService;
import com.pg.security.JwtService.TokenData;
import com.pg.user.Role;
import com.pg.user.Status;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Endpoints (same shapes as the Agro / stock-management backend):
 * POST /login, POST /refresh, POST /select-pg (super admin), POST /logout, PUT /change-password.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	/** Same message for unknown user / wrong password / blocked, so accounts cannot be enumerated. */
	private static final String BAD_CREDENTIALS = "Invalid credentials";

	private final UserRepository users;
	private final PgRepository pgs;
	private final PasswordEncoder encoder;
	private final JwtService jwt;

	public AuthController(UserRepository users, PgRepository pgs, PasswordEncoder encoder, JwtService jwt) {
		this.users = users;
		this.pgs = pgs;
		this.encoder = encoder;
		this.jwt = jwt;
	}

	public record LoginRequest(@NotBlank String username, @NotBlank String password) {
	}

	public record RegisterRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String mobile,
			@NotBlank @Size(min = 6, max = 100) String password,
			String email) {
	}

	public record SelectPgRequest(@NotNull Long pgId) {
	}

	public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank @Size(min = 8, max = 100) String newPassword) {
	}

	public record PgOption(Long id, String name) {
	}

	public record LoginResponse(String token, Role role, Long userId, String username, Long pgId, String pgName,
			Integer totalFloors, Long residentId, boolean requiresPgSelection, List<PgOption> pgs, boolean mustChangePassword) {
	}

	@PostMapping("/login")
	@Transactional
	public LoginResponse login(@Valid @RequestBody LoginRequest req) {
		String id = req.username().trim();
		UserEntity user = users.findByUsername(id).or(() -> users.findByMobile(id))
				.orElseThrow(() -> new UnauthorizedException(BAD_CREDENTIALS));
		if (!encoder.matches(req.password(), user.getPassword()) || user.getStatus() != Status.ACTIVE) {
			throw new UnauthorizedException(BAD_CREDENTIALS);
		}
		user.setLastLoginAt(LocalDateTime.now());

		if (user.getRole() == Role.SUPER_ADMIN) {
			List<PgOption> options = pgs.findAll().stream().map(p -> new PgOption(p.getId(), p.getName())).toList();
			return new LoginResponse(jwt.issue(user, null), user.getRole(), user.getId(), user.getUsername(), null, null,
					null, null, true, options, user.isMustChangePassword());
		}
		if (user.getRole() == Role.RESIDENT && user.getPgId() == null) {
			return new LoginResponse(jwt.issue(user, null), user.getRole(), user.getId(), user.getUsername(),
					null, null, null, null, false, List.of(), user.isMustChangePassword());
		}
		Pg pg = requireValidPg(user);
		return new LoginResponse(jwt.issue(user, pg.getId()), user.getRole(), user.getId(), user.getUsername(),
				pg.getId(), pg.getName(), pg.getTotalFloors(), user.getResidentId(), false, List.of(), user.isMustChangePassword());
	}

	@PostMapping("/register")
	@Transactional
	public LoginResponse register(@Valid @RequestBody RegisterRequest req) {
		String mobile = req.mobile().trim();
		if (users.existsByMobile(mobile) || users.existsByUsername(mobile)) {
			throw new BadRequestException("An account with mobile number " + mobile + " already exists. Please sign in.");
		}
		UserEntity user = new UserEntity();
		user.setUsername(mobile);
		user.setMobile(mobile);
		user.setEmail(req.email() != null && !req.email().isBlank() ? req.email().trim() : null);
		user.setPassword(encoder.encode(req.password()));
		user.setRole(Role.RESIDENT);
		user.setStatus(Status.ACTIVE);
		user.setMustChangePassword(false);
		user.setLastLoginAt(LocalDateTime.now());
		user = users.save(user);

		return new LoginResponse(jwt.issue(user, null), user.getRole(), user.getId(), user.getUsername(),
				null, null, null, null, false, List.of(), false);
	}

	/** Super admin or owner picks which PG to work in; returns a PG-scoped token. */
	@PostMapping("/select-pg")
	@Transactional
	public LoginResponse selectPg(@Valid @RequestBody SelectPgRequest req, Authentication auth) {
		UserEntity user = currentUser(auth);
		if (user.getRole() != Role.SUPER_ADMIN && user.getRole() != Role.OWNER) {
			throw new ForbiddenException("Only an admin or owner can select a PG");
		}
		Pg pg = pgs.findById(req.pgId()).orElseThrow(() -> new NotFoundException("PG not found"));
		if (user.getRole() == Role.OWNER) {
			user.setPgId(pg.getId());
			users.save(user);
		}
		return new LoginResponse(jwt.issue(user, pg.getId()), user.getRole(), user.getId(), user.getUsername(),
				pg.getId(), pg.getName(), pg.getTotalFloors(), user.getResidentId(), false, List.of(), user.isMustChangePassword());
	}

	/**
	 * Exchange a valid or recently-expired token for a fresh one. Public route: the token itself (signature +
	 * grace window + current user/PG state) is the credential.
	 */
	@PostMapping("/refresh")
	public Map<String, String> refresh(@RequestHeader(value = "Authorization", required = false) String header) {
		if (header == null || !header.startsWith("Bearer ")) {
			throw new UnauthorizedException("Missing token");
		}
		TokenData data;
		try {
			data = jwt.parseForRefresh(header.substring(7));
		} catch (JwtException | IllegalArgumentException e) {
			throw new UnauthorizedException("Invalid or expired token");
		}
		UserEntity user = users.findById(data.userId()).orElseThrow(() -> new UnauthorizedException("Invalid token"));
		if (user.getStatus() != Status.ACTIVE || user.getRole() != data.role()) {
			throw new UnauthorizedException("Invalid token");
		}
		Long pgId = data.pgId();
		if (user.getRole() == Role.SUPER_ADMIN) {
			if (pgId != null && !pgs.existsById(pgId)) {
				pgId = null;
			}
		} else {
			pgId = requireValidPg(user).getId();
		}
		return Map.of("token", jwt.issue(user, pgId));
	}

	@PostMapping("/logout")
	public ResponseEntity<Map<String, String>> logout() {
		// Tokens are stateless; the client discards its copy.
		return ResponseEntity.ok(Map.of("message", "Logged out"));
	}

	@PutMapping("/change-password")
	@Transactional
	public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication auth) {
		UserEntity user = currentUser(auth);
		if (!encoder.matches(req.oldPassword(), user.getPassword())) {
			throw new BadRequestException("Old password is incorrect");
		}
		if (req.oldPassword().equals(req.newPassword())) {
			throw new BadRequestException("New password cannot be the same as the old password");
		}
		user.setPassword(encoder.encode(req.newPassword()));
		user.setMustChangePassword(false);
		return Map.of("message", "Password updated successfully");
	}

	private UserEntity currentUser(Authentication auth) {
		return users.findByUsername(auth.getName()).orElseThrow(() -> new UnauthorizedException("Invalid token"));
	}

	private Pg requireValidPg(UserEntity user) {
		Pg pg = user.getPgId() == null ? null : pgs.findById(user.getPgId()).orElse(null);
		if (pg == null) {
			throw new UnauthorizedException(BAD_CREDENTIALS);
		}
		if (!pg.isLicenseValid()) {
			throw new LicenseExpiredException("Licence expired for this PG. Please contact the administrator.");
		}
		return pg;
	}
}
