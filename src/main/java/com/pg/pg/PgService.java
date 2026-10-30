package com.pg.pg;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.expense.ExpenseCategory;
import com.pg.expense.ExpenseCategoryRepository;
import com.pg.user.Role;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Service
public class PgService {

	private static final List<String> DEFAULT_CATEGORIES = List.of("Electricity", "Water", "Food", "Repairs",
			"Salary", "Internet", "Cleaning", "Other");

	public record PgRequest(@NotBlank @Size(max = 120) String name, String ownerName,
			@Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String mobile, String email,
			String address, String city, String state, String plan,
			/* First owner login for this PG */
			@NotBlank @Size(max = 60) String ownerUsername,
			@NotBlank @Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String ownerMobile,
			@NotBlank @Size(min = 8, max = 100) String ownerPassword) {
	}

	public record PgResponse(Long id, String name, String ownerName, String mobile, String email, String address,
			String city, String state, String plan, LocalDateTime licenseExpiry, boolean active) {
	}

	private final PgRepository pgs;
	private final UserRepository users;
	private final ExpenseCategoryRepository categories;
	private final PasswordEncoder encoder;

	public PgService(PgRepository pgs, UserRepository users, ExpenseCategoryRepository categories,
			PasswordEncoder encoder) {
		this.pgs = pgs;
		this.users = users;
		this.categories = categories;
		this.encoder = encoder;
	}

	@Transactional
	public PgResponse create(PgRequest r) {
		String ownerUsername = r.ownerUsername().trim();
		if (users.existsByUsername(ownerUsername)) {
			throw new BadRequestException("Username already in use: " + ownerUsername);
		}
		if (users.existsByMobile(r.ownerMobile())) {
			throw new BadRequestException("Mobile already in use: " + r.ownerMobile());
		}
		Pg pg = new Pg();
		pg.setName(r.name().trim());
		pg.setOwnerName(r.ownerName());
		pg.setMobile(r.mobile());
		pg.setEmail(r.email());
		pg.setAddress(r.address());
		pg.setCity(r.city());
		pg.setState(r.state());
		pg.setPlan(r.plan());
		pg.setLicenseKey(UUID.randomUUID().toString());
		pg.setLicenseStart(LocalDateTime.now());
		pg.setLicenseExpiry(LocalDateTime.now().plusMonths(1));
		pg.setActive(true);
		pg = pgs.save(pg);

		UserEntity owner = new UserEntity();
		owner.setPgId(pg.getId());
		owner.setUsername(ownerUsername);
		owner.setMobile(r.ownerMobile());
		owner.setEmail(r.email());
		owner.setPassword(encoder.encode(r.ownerPassword()));
		owner.setRole(Role.OWNER);
		owner.setMustChangePassword(true);
		users.save(owner);

		for (String name : DEFAULT_CATEGORIES) {
			ExpenseCategory c = new ExpenseCategory();
			c.setPgId(pg.getId());
			c.setName(name);
			categories.save(c);
		}
		return toResponse(pg);
	}

	public List<PgResponse> list() {
		return pgs.findAll().stream().map(this::toResponse).toList();
	}

	@Transactional
	public void setActive(Long id, boolean active) {
		get(id).setActive(active);
	}

	@Transactional
	public void extendLicense(Long id, int months) {
		Pg pg = get(id);
		// Extending an already-lapsed licence counts from now, not from the old expiry.
		LocalDateTime base = pg.getLicenseExpiry() != null && pg.getLicenseExpiry().isAfter(LocalDateTime.now())
				? pg.getLicenseExpiry()
				: LocalDateTime.now();
		pg.setLicenseExpiry(base.plusMonths(months));
	}

	private Pg get(Long id) {
		return pgs.findById(id).orElseThrow(() -> new NotFoundException("PG not found"));
	}

	private PgResponse toResponse(Pg p) {
		return new PgResponse(p.getId(), p.getName(), p.getOwnerName(), p.getMobile(), p.getEmail(), p.getAddress(),
				p.getCity(), p.getState(), p.getPlan(), p.getLicenseExpiry(), p.isActive());
	}
}
