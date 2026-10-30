package com.pg.user;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.common.ForbiddenException;
import com.pg.common.NotFoundException;
import com.pg.resident.ResidentRepository;
import com.pg.tenant.TenantContext;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** User management inside the current PG. */
@Service
public class UserService {

	public record CreateUserRequest(@NotBlank @Size(max = 60) String username, String email,
			@NotBlank @Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String mobile,
			@NotBlank @Size(min = 8, max = 100) String password, @NotNull Role role, Long residentId) {
	}

	/** All fields optional; only supplied ones change. */
	public record UpdateUserRequest(String email,
			@Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String mobile, Role role, Status status,
			@Size(min = 8, max = 100) String newPassword) {
	}

	public record UserResponse(Long id, String username, String email, String mobile, Role role, Status status,
			Long residentId, boolean mustChangePassword, LocalDateTime lastLoginAt) {
	}

	private final UserRepository users;
	private final ResidentRepository residents;
	private final PasswordEncoder encoder;

	public UserService(UserRepository users, ResidentRepository residents, PasswordEncoder encoder) {
		this.users = users;
		this.residents = residents;
		this.encoder = encoder;
	}

	public List<UserResponse> list() {
		return users.findByPgIdOrderByUsername(TenantContext.requirePgId()).stream().map(this::toResponse).toList();
	}

	public UserResponse get(Long id) {
		return toResponse(find(id));
	}

	@Transactional
	public UserResponse create(CreateUserRequest r) {
		Long pgId = TenantContext.requirePgId();
		requireAssignable(r.role());
		String username = r.username().trim();
		if (users.existsByUsername(username)) {
			throw new BadRequestException("Username already in use: " + username);
		}
		if (users.existsByMobile(r.mobile())) {
			throw new BadRequestException("Mobile already in use: " + r.mobile());
		}
		Long residentId = null;
		if (r.role() == Role.RESIDENT) {
			residentId = linkResident(pgId, r.residentId(), null);
		}
		UserEntity u = new UserEntity();
		u.setPgId(pgId);
		u.setUsername(username);
		u.setEmail(r.email());
		u.setMobile(r.mobile());
		u.setPassword(encoder.encode(r.password()));
		u.setRole(r.role());
		u.setResidentId(residentId);
		u.setMustChangePassword(true);
		return toResponse(users.save(u));
	}

	@Transactional
	public UserResponse update(Long id, UpdateUserRequest r, Authentication auth) {
		UserEntity u = find(id);
		boolean self = u.getUsername().equals(auth.getName());
		if (u.getRole() == Role.OWNER && !TenantContext.isSuperAdmin() && !self) {
			throw new ForbiddenException("Only a super admin can change another owner");
		}
		if (r.mobile() != null && !r.mobile().equals(u.getMobile())) {
			if (users.existsByMobile(r.mobile())) {
				throw new BadRequestException("Mobile already in use: " + r.mobile());
			}
			u.setMobile(r.mobile());
		}
		if (r.email() != null) {
			u.setEmail(r.email());
		}
		if (r.role() != null && r.role() != u.getRole()) {
			if (self) {
				throw new BadRequestException("You cannot change your own role");
			}
			requireAssignable(r.role());
			if (r.role() == Role.RESIDENT || u.getRole() == Role.RESIDENT) {
				throw new BadRequestException("Create a new login to switch between resident and staff");
			}
			u.setRole(r.role());
		}
		if (r.status() != null && r.status() != u.getStatus()) {
			if (self) {
				throw new BadRequestException("You cannot change your own status");
			}
			u.setStatus(r.status());
		}
		if (r.newPassword() != null) {
			u.setPassword(encoder.encode(r.newPassword()));
			u.setMustChangePassword(!self);
		}
		return toResponse(u);
	}

	@Transactional
	public void delete(Long id, Authentication auth) {
		UserEntity u = find(id);
		if (u.getUsername().equals(auth.getName())) {
			throw new BadRequestException("You cannot delete your own account");
		}
		if (u.getRole() == Role.OWNER && !TenantContext.isSuperAdmin()) {
			throw new ForbiddenException("Only a super admin can delete an owner");
		}
		users.delete(u);
	}

	private UserEntity find(Long id) {
		return users.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("User not found"));
	}

	/** SUPER_ADMIN is never creatable via the API; OWNER only by a super admin. */
	private void requireAssignable(Role role) {
		if (role == Role.SUPER_ADMIN) {
			throw new BadRequestException("Role not allowed");
		}
		if (role == Role.OWNER && !TenantContext.isSuperAdmin()) {
			throw new ForbiddenException("Only a super admin can create an owner");
		}
	}

	private Long linkResident(Long pgId, Long residentId, Long ignoreUserId) {
		if (residentId == null) {
			throw new BadRequestException("residentId is required for a RESIDENT login");
		}
		residents.findByPgIdAndId(pgId, residentId).orElseThrow(() -> new BadRequestException("Resident not found"));
		users.findByPgIdAndResidentId(pgId, residentId).ifPresent(existing -> {
			if (!existing.getId().equals(ignoreUserId)) {
				throw new BadRequestException("This resident already has a login");
			}
		});
		return residentId;
	}

	private UserResponse toResponse(UserEntity u) {
		return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getMobile(), u.getRole(), u.getStatus(),
				u.getResidentId(), u.isMustChangePassword(), u.getLastLoginAt());
	}
}
