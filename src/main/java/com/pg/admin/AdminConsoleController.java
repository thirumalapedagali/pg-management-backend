package com.pg.admin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.pg.OwnerPg;
import com.pg.pg.OwnerPgRepository;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.user.Role;
import com.pg.user.Status;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** SUPER_ADMIN console (see SecurityConfig): platform-wide figures and user management across all PGs. */
@RestController
@RequestMapping("/api/admin")
public class AdminConsoleController {

	public record Stats(long totalPgs, long activePgs, long blockedPgs, long expiredPgs, long expiringSoon, long owners,
			long managers, long residents, long blockedUsers, long pendingRegistrations) {
	}

	public record AdminUserItem(Long id, String username, String role, String status, String mobile, String email,
			Long pgId, String pgName, List<Long> pgIds, Long residentId, LocalDateTime lastLoginAt) {
	}

	/** An owner may be given several PGs; a manager exactly one. */
	public record CreateAdminUser(@NotBlank @Size(max = 60) String username, String email,
			@NotBlank @Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String mobile,
			@NotBlank @Size(min = 4, max = 100) String password, @NotNull Role role, @NotEmpty List<Long> pgIds) {
	}

	/** All optional; only supplied ones change. {@code pgIds} replaces the user's PG assignment. */
	public record UpdateAdminUser(Status status, @Size(min = 4, max = 100) String newPassword,
			@Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String mobile, String email,
			List<Long> pgIds) {
	}

	private final PgRepository pgs;
	private final UserRepository users;
	private final OwnerPgRepository ownerPgs;
	private final PasswordEncoder encoder;
	private final com.pg.registration.PropertyRegistrationRepository registrations;

	public AdminConsoleController(PgRepository pgs, UserRepository users, OwnerPgRepository ownerPgs,
			PasswordEncoder encoder, com.pg.registration.PropertyRegistrationRepository registrations) {
		this.registrations = registrations;
		this.pgs = pgs;
		this.users = users;
		this.ownerPgs = ownerPgs;
		this.encoder = encoder;
	}

	@GetMapping("/stats")
	public Stats stats() {
		LocalDateTime now = LocalDateTime.now();
		long active = 0, blocked = 0, expired = 0, soon = 0;
		List<Pg> all = pgs.findAll();
		for (Pg p : all) {
			if (!p.isActive()) {
				blocked++;
			} else if (p.getLicenseExpiry() == null || !p.getLicenseExpiry().isAfter(now)) {
				expired++;
			} else {
				active++;
				if (p.getLicenseExpiry().isBefore(now.plusDays(30))) {
					soon++;
				}
			}
		}
		return new Stats(all.size(), active, blocked, expired, soon, users.countByRole(Role.OWNER),
				users.countByRole(Role.MANAGER), users.countByRole(Role.RESIDENT), users.countByStatus(Status.BLOCKED),
				registrations.countByStatus(com.pg.registration.RegistrationStatus.PENDING));
	}

	/** Every login except super admins, with the PG(s) it belongs to. */
	@GetMapping("/users")
	public List<AdminUserItem> users() {
		Map<Long, String> names = pgs.findAll().stream().collect(Collectors.toMap(Pg::getId, Pg::getName, (a, b) -> a));
		Map<Long, List<Long>> links = ownerPgs.findAll().stream().collect(
				Collectors.groupingBy(OwnerPg::getUserId, Collectors.mapping(OwnerPg::getPgId, Collectors.toList())));
		return users.findAll().stream().filter(u -> u.getRole() != Role.SUPER_ADMIN)
				.sorted(Comparator.comparing((UserEntity u) -> u.getRole().ordinal()).thenComparing(UserEntity::getUsername))
				.map(u -> item(u, names, links.getOrDefault(u.getId(), List.of()))).toList();
	}

	@PostMapping("/users")
	@Transactional
	public AdminUserItem create(@Valid @RequestBody CreateAdminUser r) {
		if (r.role() != Role.SUPER_ADMIN && r.role() != Role.OWNER && r.role() != Role.MANAGER) {
			throw new BadRequestException("Only an owner or a manager can be created here");
		}
		String username = r.username().trim();
		if (users.existsByUsername(username)) {
			throw new BadRequestException("Username already in use: " + username);
		}
		if (users.existsByMobile(r.mobile())) {
			throw new BadRequestException("Mobile already in use: " + r.mobile());
		}
		List<Long> ids = checkedPgIds(r.role(), r.pgIds());
		UserEntity u = new UserEntity();
		u.setUsername(username);
		u.setMobile(r.mobile());
		u.setEmail(r.email() == null || r.email().isBlank() ? null : r.email().trim());
		u.setPassword(encoder.encode(r.password()));
		u.setRole(r.role());
		u.setMustChangePassword(true);
		u.setPgId(ids.get(0));
		u = users.save(u);
		syncOwnerLinks(u, ids);
		return itemOf(u);
	}

	@PutMapping("/users/{id}")
	@Transactional
	public AdminUserItem update(@PathVariable Long id, @Valid @RequestBody UpdateAdminUser r, Authentication auth) {
		UserEntity u = target(id, auth);
		if (r.mobile() != null && !r.mobile().equals(u.getMobile())) {
			if (users.existsByMobile(r.mobile())) {
				throw new BadRequestException("Mobile already in use: " + r.mobile());
			}
			u.setMobile(r.mobile());
		}
		if (r.email() != null) {
			u.setEmail(r.email().isBlank() ? null : r.email().trim());
		}
		if (r.status() != null) {
			u.setStatus(r.status());
		}
		if (r.newPassword() != null) {
			u.setPassword(encoder.encode(r.newPassword()));
			u.setMustChangePassword(true);
		}
		if (r.pgIds() != null) {
			List<Long> ids = checkedPgIds(u.getRole(), r.pgIds());
			// Keep the current home PG when it is still assigned, so existing data stays attached to it.
			Long home = ids.contains(u.getPgId()) ? u.getPgId() : ids.get(0);
			u.setPgId(home);
			List<Long> ordered = new ArrayList<>(ids);
			ordered.remove(home);
			ordered.add(0, home);
			syncOwnerLinks(u, ordered);
		}
		return itemOf(u);
	}

	@DeleteMapping("/users/{id}")
	@Transactional
	public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
		UserEntity u = target(id, auth);
		ownerPgs.deleteAll(ownerPgs.findByUserId(u.getId()));
		users.delete(u);
		return ResponseEntity.noContent().build();
	}

	/** A super admin may manage anyone except themselves or another super admin. */
	private UserEntity target(Long id, Authentication auth) {
		UserEntity u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
		if (u.getRole() == Role.SUPER_ADMIN || u.getUsername().equals(auth.getName())) {
			throw new BadRequestException("This account cannot be changed here");
		}
		return u;
	}

	/** Distinct, existing PG ids; a manager must have exactly one and a resident cannot be reassigned here. */
	private List<Long> checkedPgIds(Role role, List<Long> requested) {
		if (role != Role.OWNER && role != Role.MANAGER) {
			throw new BadRequestException("Resident logins belong to one PG and are managed by its owner");
		}
		List<Long> ids = new ArrayList<>(new LinkedHashSet<>(requested));
		if (ids.isEmpty()) {
			throw new BadRequestException("Select at least one PG");
		}
		if (role == Role.MANAGER && ids.size() != 1) {
			throw new BadRequestException("A manager belongs to exactly one PG");
		}
		Set<Long> found = pgs.findAllById(ids).stream().map(Pg::getId).collect(Collectors.toSet());
		if (!found.containsAll(ids)) {
			throw new BadRequestException("PG not found");
		}
		return ids;
	}

	/** An owner's home PG is {@code pgId}; every other PG is a row in owner_pgs. {@code ids[0]} is the home PG. */
	private void syncOwnerLinks(UserEntity u, List<Long> ids) {
		if (u.getRole() != Role.OWNER) {
			return;
		}
		Set<Long> wanted = new LinkedHashSet<>(ids);
		wanted.remove(u.getPgId());
		List<OwnerPg> existing = ownerPgs.findByUserId(u.getId());
		ownerPgs.deleteAll(existing.stream().filter(l -> !wanted.contains(l.getPgId())).toList());
		Set<Long> have = existing.stream().map(OwnerPg::getPgId).collect(Collectors.toSet());
		wanted.stream().filter(p -> !have.contains(p)).forEach(p -> ownerPgs.save(new OwnerPg(u.getId(), p)));
	}

	private AdminUserItem itemOf(UserEntity u) {
		Map<Long, String> names = pgs.findAll().stream().collect(Collectors.toMap(Pg::getId, Pg::getName, (a, b) -> a));
		List<Long> links = ownerPgs.findByUserId(u.getId()).stream().map(OwnerPg::getPgId).toList();
		return item(u, names, links);
	}

	private AdminUserItem item(UserEntity u, Map<Long, String> names, List<Long> links) {
		Set<Long> ids = new LinkedHashSet<>();
		if (u.getPgId() != null) {
			ids.add(u.getPgId());
		}
		if (u.getRole() == Role.OWNER) {
			ids.addAll(links);
		}
		String pgName = ids.stream().map(names::get).filter(n -> n != null).collect(Collectors.joining(", "));
		return new AdminUserItem(u.getId(), u.getUsername(), u.getRole().name(), u.getStatus().name(), u.getMobile(),
				u.getEmail(), u.getPgId(), pgName.isEmpty() ? null : pgName, List.copyOf(ids), u.getResidentId(),
				u.getLastLoginAt());
	}
}
