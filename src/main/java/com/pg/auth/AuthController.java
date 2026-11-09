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
import com.pg.pg.OwnerPgAccess;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.registration.PropertyRegistrationService;
import com.pg.registration.RegistrationStatus;
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
	private final OwnerPgAccess access;
	private final PropertyRegistrationService registrations;

	public AuthController(UserRepository users, PgRepository pgs, PasswordEncoder encoder, JwtService jwt,
			OwnerPgAccess access, PropertyRegistrationService registrations) {
		this.registrations = registrations;
		this.users = users;
		this.pgs = pgs;
		this.encoder = encoder;
		this.jwt = jwt;
		this.access = access;
	}

	public record LoginRequest(@NotBlank String username, @NotBlank String password) {
	}

	public record SelectPgRequest(@NotNull Long pgId) {
	}

	public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank @Size(min = 4, max = 100) String newPassword) {
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
				.orElseThrow(() -> unknownLogin(id, req.password()));
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
			throw new ForbiddenException("You can sign in once a stay is allotted to you. Until then, browse stays from the sign-in screen.");
		}
		Pg pg = requireValidPg(user);
		return new LoginResponse(jwt.issue(user, pg.getId()), user.getRole(), user.getId(), user.getUsername(),
				pg.getId(), pg.getName(), pg.getTotalFloors(), user.getResidentId(), false, List.of(), user.isMustChangePassword());
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
			// An owner may only open their own properties.
			if (!access.owns(user, pg.getId())) {
				throw new ForbiddenException("This property does not belong to you");
			}
			requireValidPg(pg);
		}
		return new LoginResponse(jwt.issue(user, pg.getId()), user.getRole(), user.getId(), user.getUsername(),
				pg.getId(), pg.getName(), pg.getTotalFloors(), user.getResidentId(), false, List.of(), user.isMustChangePassword());
	}


	/** Super admin leaves the property they entered and goes back to the admin console (token without a PG). */
	@PostMapping("/exit-pg")
	@Transactional
	public LoginResponse exitPg(Authentication auth) {
		UserEntity user = currentUser(auth);
		if (user.getRole() != Role.SUPER_ADMIN) {
			throw new ForbiddenException("Only an admin can leave a PG");
		}
		List<PgOption> options = pgs.findAll().stream().map(p -> new PgOption(p.getId(), p.getName())).toList();
		return new LoginResponse(jwt.issue(user, null), user.getRole(), user.getId(), user.getUsername(), null, null,
				null, null, true, options, user.isMustChangePassword());
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
		} else if (user.getRole() == Role.OWNER && access.owns(user, pgId)) {
			// Stay in the property the owner switched to.
			pgId = requireValidPg(pgs.findById(pgId).orElse(null)).getId();
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

	/** Unknown login: say so, except when it is an owner whose registration is still waiting for (or was refused by) the administrator. */
	private RuntimeException unknownLogin(String id, String password) {
		return registrations.findFor(id, password).filter(r -> r.getStatus() != RegistrationStatus.APPROVED).map(r -> {
			if (r.getStatus() == RegistrationStatus.PENDING) {
				return (RuntimeException) new ForbiddenException("Your registration is waiting for the administrator's approval. You can sign in once it is approved.");
			}
			String why = r.getRejectionReason() == null ? "" : " Reason: " + r.getRejectionReason();
			return (RuntimeException) new ForbiddenException("Your registration was not approved." + why);
		}).orElseGet(() -> new UnauthorizedException(BAD_CREDENTIALS));
	}

	private UserEntity currentUser(Authentication auth) {
		return users.findByUsername(auth.getName()).orElseThrow(() -> new UnauthorizedException("Invalid token"));
	}

	private Pg requireValidPg(UserEntity user) {
		return requireValidPg(user.getPgId() == null ? null : pgs.findById(user.getPgId()).orElse(null));
	}

	private Pg requireValidPg(Pg pg) {
		if (pg == null) {
			throw new UnauthorizedException(BAD_CREDENTIALS);
		}
		if (!pg.isLicenseValid()) {
			throw new LicenseExpiredException("Licence expired for this PG. Please contact the administrator.");
		}
		return pg;
	}
}
