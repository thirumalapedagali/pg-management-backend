package com.pg.registration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.pg.PgService;
import com.pg.pg.PgService.PgRequest;
import com.pg.push.PushService;
import com.pg.user.UserRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owners ask to join; the super admin approves, which creates the property and the owner's login. */
@Service
public class PropertyRegistrationService {

	/** What an owner fills in on the sign-up form. Pictures are added after approval, from the owner's own account. */
	public record SubmitRequest(
			@NotBlank @Size(max = 120) String ownerName,
			@NotBlank @Pattern(regexp = "\\d{10,15}", message = "must be 10-15 digits") String mobile,
			@NotBlank @Size(min = 4, max = 100) String password,
			@Size(max = 120) String email,
			@NotBlank @Size(max = 120) String name,
			@Pattern(regexp = "^$|\\d{10,15}", message = "must be 10-15 digits") String contactMobile,
			@Size(max = 300) String address, @Size(max = 80) String city, @Size(max = 80) String state,
			Integer totalFloors, @Size(max = 40) String stayType, @Size(max = 40) String unitLabel,
			@Size(max = 1000) String locationUrl, @Size(max = 30) String genderType,
			@Size(max = 100) String upiId, @Size(max = 150) String upiName,
			@Size(max = 2000) String description, BigDecimal startingPrice, List<String> amenityCodes,
			Double latitude, Double longitude, String locality, String subLocality, String pincode, String country,
			@Pattern(regexp = "^$|\\d{10,15}", message = "must be 10-15 digits") String alternateMobile,
			@Size(max = 4000) String rules) {
	}

	public record RegistrationItem(Long id, String propertyName, String ownerName, String mobile, String email,
			String stayType, String address, String city, String upiId, BigDecimal startingPrice,
			RegistrationStatus status, String rejectionReason, Long pgId, LocalDateTime createdAt,
			LocalDateTime decidedAt) {
	}

	private final PropertyRegistrationRepository registrations;
	private final UserRepository users;
	private final PgService pgService;
	private final PasswordEncoder encoder;
	private final PushService push;
	private final ObjectMapper json = new ObjectMapper();

	public PropertyRegistrationService(PropertyRegistrationRepository registrations, UserRepository users,
			PgService pgService, PasswordEncoder encoder, PushService push) {
		this.registrations = registrations;
		this.users = users;
		this.pgService = pgService;
		this.encoder = encoder;
		this.push = push;
	}

	@Transactional
	public void submit(SubmitRequest r) {
		String mobile = r.mobile().trim();
		if (users.existsByMobile(mobile) || users.existsByUsername(mobile)) {
			throw new BadRequestException("An account with mobile number " + mobile + " already exists. Please sign in.");
		}
		if (registrations.existsByMobileAndStatus(mobile, RegistrationStatus.PENDING)) {
			throw new BadRequestException("A registration for " + mobile + " is already waiting for approval.");
		}
		if (r.latitude() == null || r.longitude() == null) {
			throw new BadRequestException("Please pick the property location on the map.");
		}
		if (r.totalFloors() == null || r.totalFloors() < 1) {
			throw new BadRequestException("Please enter the number of floors.");
		}

		String contact = r.contactMobile() == null || r.contactMobile().isBlank() ? mobile : r.contactMobile().trim();
		PgRequest pg = new PgRequest(r.name().trim(), r.ownerName().trim(), contact, blankToNull(r.email()),
				blankToNull(r.address()), blankToNull(r.city()), blankToNull(r.state()), null, r.totalFloors(),
				r.stayType(), r.unitLabel(), blankToNull(r.locationUrl()), r.genderType(), blankToNull(r.upiId()),
				blankToNull(r.upiName()), r.description(), null, r.startingPrice(), mobile, mobile,
				/* the password is kept only as a hash, on the registration row */ "-", r.amenityCodes(), r.latitude(),
				r.longitude(), r.locality(), r.subLocality(), r.pincode(), r.country(), blankToNull(r.alternateMobile()),
				blankToNull(r.rules()));

		PropertyRegistration reg = new PropertyRegistration();
		reg.setPropertyName(pg.name());
		reg.setOwnerName(r.ownerName().trim());
		reg.setMobile(mobile);
		reg.setPasswordHash(encoder.encode(r.password()));
		reg.setPayload(json.writeValueAsString(pg));
		registrations.save(reg);

		try {
			push.toSuperAdmins("New Owner Registration", "New registration request received from " + r.ownerName().trim() + " for " + r.name().trim() + ".");
			push.toUserByMobile(null, mobile, "Registration Submitted", "Your registration request for " + r.name().trim() + " has been submitted successfully!");
		} catch (Exception e) {
			// Fail-safe
		}
	}

	public List<RegistrationItem> list(RegistrationStatus status) {
		List<PropertyRegistration> rows = status == null ? registrations.findAllByOrderByIdDesc()
				: registrations.findByStatusOrderByIdDesc(status);
		return rows.stream().map(this::item).toList();
	}

	@Transactional
	public RegistrationItem approve(Long id) {
		PropertyRegistration reg = pending(id);
		String mobile = reg.getMobile();
		if (users.existsByMobile(mobile) || users.existsByUsername(mobile)) {
			throw new BadRequestException(
					"An account with mobile number " + mobile + " exists now. Reject this request or remove that account first.");
		}
		PgRequest pg = json.readValue(reg.getPayload(), PgRequest.class);
		// The owner chose the password, so there is nothing to change at first sign-in.
		Long pgId = pgService.create(pg, reg.getPasswordHash(), false).id();
		reg.setStatus(RegistrationStatus.APPROVED);
		reg.setPgId(pgId);
		reg.setDecidedAt(LocalDateTime.now());

		try {
			push.toUserByMobile(pgId, reg.getMobile(), "Registration Approved", "Congratulations! Your registration request for " + reg.getPropertyName() + " has been approved!");
		} catch (Exception e) {
			// Fail-safe
		}

		return item(reg);
	}

	@Transactional
	public RegistrationItem reject(Long id, String reason) {
		PropertyRegistration reg = pending(id);
		reg.setStatus(RegistrationStatus.REJECTED);
		reg.setRejectionReason(reason == null || reason.isBlank() ? null : reason.trim());
		reg.setDecidedAt(LocalDateTime.now());

		try {
			String reasonText = (reason != null && !reason.isBlank()) ? " Reason: " + reason.trim() : "";
			push.toUserByMobile(null, reg.getMobile(), "Registration Rejected", "Your registration request for " + reg.getPropertyName() + " has been rejected." + reasonText);
		} catch (Exception e) {
			// Fail-safe
		}

		return item(reg);
	}

	public long pendingCount() {
		return registrations.countByStatus(RegistrationStatus.PENDING);
	}

	/** The latest registration made with this mobile, if the password is the one used then. */
	public java.util.Optional<PropertyRegistration> findFor(String mobile, String password) {
		return registrations.findFirstByMobileOrderByIdDesc(mobile)
				.filter(r -> encoder.matches(password, r.getPasswordHash()));
	}

	private PropertyRegistration pending(Long id) {
		PropertyRegistration reg = registrations.findById(id)
				.orElseThrow(() -> new NotFoundException("Registration not found"));
		if (reg.getStatus() != RegistrationStatus.PENDING) {
			throw new BadRequestException("This request has already been " + reg.getStatus().name().toLowerCase());
		}
		return reg;
	}

	private RegistrationItem item(PropertyRegistration r) {
		JsonNode p = json.readTree(r.getPayload());
		return new RegistrationItem(r.getId(), r.getPropertyName(), r.getOwnerName(), r.getMobile(), text(p, "email"),
				text(p, "stayType"), text(p, "address"), text(p, "city"), text(p, "upiId"),
				p.hasNonNull("startingPrice") ? p.get("startingPrice").decimalValue() : null, r.getStatus(),
				r.getRejectionReason(), r.getPgId(), r.getCreatedAt(), r.getDecidedAt());
	}

	private static String text(JsonNode n, String field) {
		return n.hasNonNull(field) ? n.get(field).asString() : null;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
