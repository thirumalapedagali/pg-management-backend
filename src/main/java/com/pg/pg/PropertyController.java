package com.pg.pg;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.ForbiddenException;
import com.pg.config.PropertyConfigurationService;
import com.pg.dashboard.DashboardCache;
import com.pg.expense.ExpenseCategory;
import com.pg.expense.ExpenseCategoryRepository;
import com.pg.lookup.LookupService;
import com.pg.resident.ResidentRepository;
import com.pg.resident.ResidentStatus;
import com.pg.room.RoomRepository;
import com.pg.user.Role;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An owner's own properties: list them for the property switcher, and add another one. */
@RestController
@RequestMapping("/api/pg/properties")
public class PropertyController {

	private static final List<String> DEFAULT_CATEGORIES = List.of("Electricity", "Water", "Food", "Repairs",
			"Salary", "Internet", "Cleaning", "Other");

	public record PropertyCard(Long id, String name, String city, String address, String stayType, int rooms, int beds,
			int residents, boolean licenseValid) {
	}

	public record NewPropertyRequest(@NotBlank @Size(max = 120) String name, String stayType, String address,
			String city, String state, Integer totalFloors, String genderType, String unitLabel, String locationUrl,
			String upiId, String upiName, String mobile,
			@jakarta.validation.constraints.Pattern(regexp = "|\\d{10,15}", message = "must be 10-15 digits") String alternateMobile,
			java.math.BigDecimal startingPrice, String description,
			List<String> amenityCodes, Double latitude, Double longitude, String locality, String subLocality,
			String pincode, String country, @Size(max = 4000) String rules) {
	}

	private final UserRepository users;
	private final PgRepository pgs;
	private final OwnerPgAccess access;
	private final RoomRepository rooms;
	private final ResidentRepository residents;
	private final ExpenseCategoryRepository categories;
	private final DashboardCache cache;
	private final LookupService lookups;
	private final PropertyConfigurationService propertyConfig;

	public PropertyController(UserRepository users, PgRepository pgs, OwnerPgAccess access, RoomRepository rooms,
			ResidentRepository residents, ExpenseCategoryRepository categories, DashboardCache cache,
			LookupService lookups, PropertyConfigurationService propertyConfig) {
		this.propertyConfig = propertyConfig;
		this.cache = cache;
		this.lookups = lookups;
		this.users = users;
		this.pgs = pgs;
		this.access = access;
		this.rooms = rooms;
		this.residents = residents;
		this.categories = categories;
	}

	@GetMapping
	public List<PropertyCard> mine(Authentication auth) {
		UserEntity owner = owner(auth);
		List<Pg> owned = pgs.findAllById(access.ownedPgIds(owner)).stream()
				.sorted(Comparator.comparing(Pg::getName, String.CASE_INSENSITIVE_ORDER)).toList();
		return cache.parallelMap(owned, this::card);
	}

	@PostMapping
	@Transactional
	public PropertyCard add(@Valid @RequestBody NewPropertyRequest r, Authentication auth) {
		UserEntity owner = owner(auth);
		Pg home = owner.getPgId() == null ? null : pgs.findById(owner.getPgId()).orElse(null);

		Pg pg = new Pg();
		pg.setName(r.name().trim());
		pg.setOwnerName(home != null ? home.getOwnerName() : owner.getUsername());
		pg.setMobile(r.mobile() != null && !r.mobile().isBlank() ? r.mobile().trim() : owner.getMobile());
		pg.setAlternateMobile(blankToNull(r.alternateMobile()));
		pg.setEmail(owner.getEmail());
		pg.setAddress(blankToNull(r.address()));
		pg.setCity(blankToNull(r.city()));
		pg.setState(blankToNull(r.state()));
		pg.setPlan(home != null ? home.getPlan() : null);
		if (r.totalFloors() == null || r.totalFloors() < 1) {
			throw new com.pg.common.BadRequestException("Number of floors is required");
		}
		pg.setTotalFloors(r.totalFloors());
		String stay = r.stayType() != null && !r.stayType().isBlank() ? r.stayType().trim().toUpperCase() : "PG";
		pg.setStayType(stay);
		pg.setUnitLabel(r.unitLabel() != null && !r.unitLabel().isBlank() ? r.unitLabel().trim()
				: lookups.defaultUnitLabel(stay));
		pg.setLocationUrl(blankToNull(r.locationUrl()));
		pg.setUpiId(blankToNull(r.upiId()));
		pg.setUpiName(blankToNull(r.upiName()));
		pg.setDescription(blankToNull(r.description()));
		pg.setStartingPrice(r.startingPrice());
		pg.setRules(blankToNull(r.rules()));
		PgLocation.apply(pg, r.latitude(), r.longitude(), r.locality(), r.subLocality(), r.pincode(), r.country());
		pg.setGenderType(r.genderType() != null && !r.genderType().isBlank() ? r.genderType().trim().toUpperCase()
				: "CO_LIVING");
		pg.setLicenseKey(UUID.randomUUID().toString());
		// A new property runs on the owner's existing licence.
		pg.setLicenseStart(home != null && home.getLicenseStart() != null ? home.getLicenseStart() : LocalDateTime.now());
		pg.setLicenseExpiry(home != null && home.getLicenseExpiry() != null ? home.getLicenseExpiry()
				: LocalDateTime.now().plusMonths(1));
		pg.setActive(true);
		pg.setAmenities(String.join(", ", r.amenityCodes()));
		pg = pgs.save(pg);
//		if (r.amenityCodes() != null) {
//			propertyConfig.applyAmenities(pg, r.amenityCodes());
//		}

		access.link(owner.getId(), pg.getId());
		for (String name : DEFAULT_CATEGORIES) {
			ExpenseCategory c = new ExpenseCategory();
			c.setPgId(pg.getId());
			c.setName(name);
			categories.save(c);
		}
		return card(pg);
	}

	private PropertyCard card(Pg p) {
		Object[] stats = rooms.stats(p.getId()).get(0);
		int active = (int) residents.countByPgIdAndStatus(p.getId(), ResidentStatus.ACTIVE);
		return new PropertyCard(p.getId(), p.getName(), p.getCity(), p.getAddress(),
				p.getStayType() != null ? p.getStayType() : "PG", ((Number) stats[0]).intValue(),
				((Number) stats[1]).intValue(), active, p.isLicenseValid());
	}

	private UserEntity owner(Authentication auth) {
		UserEntity user = users.findByUsername(auth.getName()).orElseThrow(() -> new ForbiddenException("Unknown user"));
		if (user.getRole() != Role.OWNER) {
			throw new ForbiddenException("Only an owner can manage properties");
		}
		return user;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
