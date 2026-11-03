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
			String address, String city, String state, String plan, Integer totalFloors,
			String stayType, String unitLabel, String locationUrl,
			String genderType, String upiId, String upiName, String description, String amenities,
			java.math.BigDecimal startingPrice,
			/* First owner login for this PG */
			@NotBlank @Size(max = 60) String ownerUsername,
			@NotBlank @Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String ownerMobile,
			@NotBlank @Size(min = 8, max = 100) String ownerPassword) {
	}

	public record UpdatePgProfileRequest(@NotBlank @Size(max = 120) String name, String ownerName,
			String mobile, String email, String address, String city, String state, Integer totalFloors,
			String stayType, String unitLabel, String locationUrl,
			String genderType, String upiId, String upiName, String description, String amenities,
			java.math.BigDecimal startingPrice) {
	}

	public record PgResponse(Long id, String name, String ownerName, String mobile, String email, String address,
			String city, String state, String plan, Integer totalFloors,
			String stayType, String unitLabel, String locationUrl,
			String genderType, String upiId, String upiName, String description, String amenities,
			java.math.BigDecimal startingPrice, LocalDateTime licenseExpiry, boolean active) {
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
		pg.setTotalFloors(r.totalFloors() != null && r.totalFloors() > 0 ? r.totalFloors() : 3);
		pg.setStayType(r.stayType() != null && !r.stayType().isBlank() ? r.stayType().trim().toUpperCase() : "PG");
		String defaultUnit = "APARTMENT".equalsIgnoreCase(r.stayType()) ? "Flat" : "HOSTEL".equalsIgnoreCase(r.stayType()) ? "Dorm" : "Room";
		pg.setUnitLabel(r.unitLabel() != null && !r.unitLabel().isBlank() ? r.unitLabel().trim() : defaultUnit);
		pg.setLocationUrl(r.locationUrl() != null && !r.locationUrl().isBlank() ? r.locationUrl().trim() : null);
		pg.setGenderType(r.genderType() != null && !r.genderType().isBlank() ? r.genderType().trim() : "CO_LIVING");
		pg.setUpiId(r.upiId() != null && !r.upiId().isBlank() ? r.upiId().trim() : null);
		pg.setUpiName(r.upiName() != null && !r.upiName().isBlank() ? r.upiName().trim() : null);
		pg.setDescription(r.description());
		pg.setAmenities(r.amenities());
		pg.setStartingPrice(r.startingPrice());
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

	public PgResponse getProfile(Long id) {
		return toResponse(get(id));
	}

	@Transactional
	public PgResponse updateProfile(Long id, UpdatePgProfileRequest req) {
		Pg pg = get(id);
		pg.setName(req.name().trim());
		if (req.ownerName() != null) pg.setOwnerName(req.ownerName().trim());
		if (req.mobile() != null) pg.setMobile(req.mobile().trim());
		if (req.email() != null) pg.setEmail(req.email().trim());
		if (req.address() != null) pg.setAddress(req.address().trim());
		if (req.city() != null) pg.setCity(req.city().trim());
		if (req.state() != null) pg.setState(req.state().trim());
		if (req.totalFloors() != null && req.totalFloors() > 0) pg.setTotalFloors(req.totalFloors());
		if (req.stayType() != null && !req.stayType().isBlank()) pg.setStayType(req.stayType().trim().toUpperCase());
		if (req.unitLabel() != null && !req.unitLabel().isBlank()) pg.setUnitLabel(req.unitLabel().trim());
		if (req.locationUrl() != null) pg.setLocationUrl(req.locationUrl().trim());
		if (req.genderType() != null && !req.genderType().isBlank()) pg.setGenderType(req.genderType().trim());
		if (req.upiId() != null) pg.setUpiId(req.upiId().trim());
		if (req.upiName() != null) pg.setUpiName(req.upiName().trim());
		if (req.description() != null) pg.setDescription(req.description().trim());
		if (req.amenities() != null) pg.setAmenities(req.amenities().trim());
		if (req.startingPrice() != null) pg.setStartingPrice(req.startingPrice());
		return toResponse(pgs.save(pg));
	}

	private Pg get(Long id) {
		return pgs.findById(id).orElseThrow(() -> new NotFoundException("PG not found"));
	}

	private PgResponse toResponse(Pg p) {
		return new PgResponse(p.getId(), p.getName(), p.getOwnerName(), p.getMobile(), p.getEmail(), p.getAddress(),
				p.getCity(), p.getState(), p.getPlan(), p.getTotalFloors(),
				p.getStayType() != null ? p.getStayType() : "PG",
				p.getUnitLabel() != null ? p.getUnitLabel() : "Room",
				p.getLocationUrl(),
				p.getGenderType(), p.getUpiId(),
				p.getUpiName(), p.getDescription(), p.getAmenities(), p.getStartingPrice(), p.getLicenseExpiry(),
				p.isActive());
	}
}
