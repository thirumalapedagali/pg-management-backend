package com.pg.resident;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.billing.Billing;
import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.Paged;
import com.pg.common.NotFoundException;
import com.pg.notification.WhatsAppService;
import com.pg.push.PushService;
import com.pg.payment.PaymentRepository;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.room.Bed;
import com.pg.room.BedRepository;
import com.pg.room.BedStatus;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.room.RoomSection;
import com.pg.room.RoomSectionRepository;
import com.pg.room.RoomSharingOption;
import com.pg.room.RoomSharingOptionRepository;
import com.pg.room.SharingType;
import com.pg.tenant.TenantContext;
import com.pg.user.Role;
import com.pg.user.Status;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ResidentService {

	public record ResidentRequest(@NotBlank @Size(max = 120) String name,
			@Pattern(regexp = "^$|\\d{10,15}", message = "must be 10-15 digits") String phone, @NotNull Long roomId,
			Long sectionId, Long bedId, Long sharingOptionId, StayType stayType, BigDecimal dailyRate,
			LocalDate checkOutDate, java.time.LocalDateTime checkInTime, java.time.LocalDateTime checkOutTime,
			BigDecimal rentOverride, BigDecimal securityDeposit, LocalDate joinedOn,
			@Pattern(regexp = "^$|\\d{12}", message = "must be 12 digits") String aadhaar) {
	}

	public record MoveResidentRequest(@NotNull Long roomId, Long sectionId, Long bedId, Long sharingOptionId,
			StayType stayType, BigDecimal dailyRate, LocalDate checkOutDate, java.time.LocalDateTime checkOutTime,
			BigDecimal rentOverride, BigDecimal securityDeposit) {
	}

	public record RejoinResidentRequest(@NotNull Long roomId, Long sectionId, Long bedId, Long sharingOptionId,
			StayType stayType, BigDecimal dailyRate, java.time.LocalDateTime checkInTime,
			BigDecimal rentOverride, BigDecimal securityDeposit, LocalDate joinedOn) {
	}

	/** A rent that takes effect from the start of a billing cycle. */
	public record RentChangeInfo(LocalDate effectiveFrom, BigDecimal rent) {
	}

	/**
	 * rent = the rent for the current cycle. baseRent + rentChanges let clients price every past/future cycle.
	 */
	public record ResidentResponse(Long id, String name, String phone, Long roomId, String roomNumber,
			Long sectionId, String sectionName, Long bedId, String bedNumber,
			Long sharingOptionId, SharingType sharingType, StayType stayType, BigDecimal dailyRate,
			LocalDate checkOutDate, java.time.LocalDateTime checkInTime, java.time.LocalDateTime checkOutTime,
			int daysStayed, Double hoursStayed,
			LocalDate joinedOn, BigDecimal rent, BigDecimal baseRent, List<RentChangeInfo> rentChanges,
			BigDecimal securityDeposit, BigDecimal returnedDeposit, BigDecimal billed, BigDecimal paid, BigDecimal due,
			ResidentStatus status, NoticeStatus noticeStatus, LocalDate noticeGivenOn, LocalDate vacateOn,
			String noticeReason, String photoUrl, String attachmentUrl) {
	}

	public record ResidentSummaryResponse(
			long totalResidents,
			BigDecimal totalRent,
			BigDecimal totalDue,
			long withDues,
			long paidCount) {
	}

	/** Minimum days between giving notice and leaving. */
	public static final int NOTICE_DAYS = 30;

	private final ResidentRepository residents;
	private final RoomRepository rooms;
	private final RoomSectionRepository sections;
	private final BedRepository beds;
	private final RoomSharingOptionRepository sharingOptions;
	private final PaymentRepository payments;
	private final UserRepository users;
	private final PgRepository pgs;
	private final Billing billing;
	private final ResidentRentChangeRepository rentChanges;
	private final WhatsAppService whatsAppService;
	private final AppClock clock;
	private final com.pg.attachment.MediaService media;
	private final PasswordEncoder encoder;
	private final PushService push;

	public ResidentService(ResidentRepository residents, RoomRepository rooms, RoomSectionRepository sections,
			BedRepository beds, RoomSharingOptionRepository sharingOptions, PaymentRepository payments,
			UserRepository users, PgRepository pgs, Billing billing, ResidentRentChangeRepository rentChanges,
			WhatsAppService whatsAppService, AppClock clock, com.pg.attachment.MediaService media,
			PasswordEncoder encoder, PushService push) {
		this.encoder = encoder;
		this.media = media;
		this.rentChanges = rentChanges;
		this.residents = residents;
		this.rooms = rooms;
		this.sections = sections;
		this.beds = beds;
		this.sharingOptions = sharingOptions;
		this.payments = payments;
		this.users = users;
		this.pgs = pgs;
		this.billing = billing;
		this.whatsAppService = whatsAppService;
		this.clock = clock;
		this.push = push;
	}

	public List<ResidentResponse> list() {
		return list("ACTIVE");
	}

	public List<ResidentResponse> list(String statusFilter) {
		return list(TenantContext.requirePgId(), statusFilter);
	}

	/** Same as {@link #list(String)} for an explicit PG, so callers need not switch the tenant context. */
	public List<ResidentResponse> list(Long pgId, String statusFilter) {
		checkAutoCheckouts(pgId);
		Map<Long, Room> roomById = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, Function.identity()));
		Map<Long, RoomSection> sectionById = sections.findByPgIdOrderBySectionName(pgId).stream()
				.collect(Collectors.toMap(RoomSection::getId, Function.identity()));
		Map<Long, Bed> bedById = beds.findByPgIdOrderByBedNumber(pgId).stream()
				.collect(Collectors.toMap(Bed::getId, Function.identity()));
		Map<Long, RoomSharingOption> optionById = sharingOptions.findByPgIdOrderByCapacity(pgId).stream()
				.collect(Collectors.toMap(RoomSharingOption::getId, Function.identity()));
		Map<Long, BigDecimal> paid = new java.util.HashMap<>();
		for (Object[] row : payments.sumByResident(pgId)) {
			paid.put((Long) row[0], (BigDecimal) row[1]);
		}
		Map<Long, List<ResidentRentChange>> changesByResident = rentChanges.findByPgIdOrderByEffectiveFrom(pgId).stream()
				.collect(Collectors.groupingBy(ResidentRentChange::getResidentId));

		Map<Long, String> photos = media.residentPhotos(pgId);
		List<Resident> resList;
		if ("ALL".equalsIgnoreCase(statusFilter)) {
			resList = residents.findByPgIdOrderByName(pgId);
		} else if ("CHECKED_OUT".equalsIgnoreCase(statusFilter) || "EX".equalsIgnoreCase(statusFilter)) {
			resList = residents.findByPgIdAndStatusOrderByName(pgId, ResidentStatus.CHECKED_OUT);
		} else {
			resList = residents.findByPgIdAndStatusOrderByName(pgId, ResidentStatus.ACTIVE);
		}

		return resList.stream()
				.map(r -> toResponse(r, roomById.get(r.getRoomId()),
						r.getSectionId() != null ? sectionById.get(r.getSectionId()) : null,
						r.getBedId() != null ? bedById.get(r.getBedId()) : null,
						r.getSharingOptionId() != null ? optionById.get(r.getSharingOptionId()) : null,
						paid.getOrDefault(r.getId(), BigDecimal.ZERO),
						changesByResident.getOrDefault(r.getId(), List.of()), photos.get(r.getId())))
				.toList();
	}

	public Paged<ResidentResponse> listPaged(String statusFilter, String search, String filter, String floor, int page, int size) {
		return listPaged(TenantContext.requirePgId(), statusFilter, search, filter, floor, page, size);
	}

	public Paged<ResidentResponse> listPaged(Long pgId, String statusFilter, String search, String filter, String floor, int page, int size) {
		checkAutoCheckouts(pgId);
		ResidentStatus status = null;
		if (!"ALL".equalsIgnoreCase(statusFilter)) {
			if ("CHECKED_OUT".equalsIgnoreCase(statusFilter) || "EX".equalsIgnoreCase(statusFilter)) {
				status = ResidentStatus.CHECKED_OUT;
			} else {
				status = ResidentStatus.ACTIVE;
			}
		}

		Pageable pageable = PageRequest.of(page, size);

		if ("WITH_DUES".equalsIgnoreCase(filter) || "PAID_UP".equalsIgnoreCase(filter)) {
			List<Resident> matchedResidents = residents.searchResidentsList(pgId, status, floor, search);

			Map<Long, Room> roomById = rooms.findByPgIdOrderByNumber(pgId).stream()
					.collect(Collectors.toMap(Room::getId, Function.identity()));
			Map<Long, BigDecimal> paid = new java.util.HashMap<>();
			for (Object[] row : payments.sumByResident(pgId)) {
				paid.put((Long) row[0], (BigDecimal) row[1]);
			}
			Map<Long, List<ResidentRentChange>> changesByResident = rentChanges.findByPgIdOrderByEffectiveFrom(pgId).stream()
					.collect(Collectors.groupingBy(ResidentRentChange::getResidentId));

			List<Resident> filtered = matchedResidents.stream().filter(r -> {
				BigDecimal billed = billing.billed(r, roomById.get(r.getRoomId()), changesByResident.getOrDefault(r.getId(), List.of()));
				BigDecimal due = Billing.due(billed, paid.getOrDefault(r.getId(), BigDecimal.ZERO));
				if ("WITH_DUES".equalsIgnoreCase(filter)) {
					return due.compareTo(BigDecimal.ZERO) > 0;
				} else {
					return due.compareTo(BigDecimal.ZERO) == 0;
				}
			}).toList();

			long totalElements = filtered.size();
			int totalPages = (int) Math.ceil((double) totalElements / size);

			int fromIndex = page * size;
			if (fromIndex >= totalElements) {
				return new Paged<>(List.of(), page, size, totalElements, totalPages);
			}
			int toIndex = Math.min(fromIndex + size, (int) totalElements);
			List<Resident> pagedContent = filtered.subList(fromIndex, toIndex);

			List<ResidentResponse> responses = mapToResponses(pgId, pagedContent);
			return new Paged<>(responses, page, size, totalElements, totalPages);
		} else {
			Page<Resident> pagedResult = residents.searchResidentsPaged(pgId, status, floor, search, pageable);
			List<ResidentResponse> responses = mapToResponses(pgId, pagedResult.getContent());
			return new Paged<>(responses, pagedResult.getNumber(), pagedResult.getSize(), pagedResult.getTotalElements(), pagedResult.getTotalPages());
		}
	}

	private List<ResidentResponse> mapToResponses(Long pgId, List<Resident> resList) {
		Map<Long, Room> roomById = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, Function.identity()));
		Map<Long, RoomSection> sectionById = sections.findByPgIdOrderBySectionName(pgId).stream()
				.collect(Collectors.toMap(RoomSection::getId, Function.identity()));
		Map<Long, Bed> bedById = beds.findByPgIdOrderByBedNumber(pgId).stream()
				.collect(Collectors.toMap(Bed::getId, Function.identity()));
		Map<Long, RoomSharingOption> optionById = sharingOptions.findByPgIdOrderByCapacity(pgId).stream()
				.collect(Collectors.toMap(RoomSharingOption::getId, Function.identity()));
		Map<Long, BigDecimal> paid = new java.util.HashMap<>();
		for (Object[] row : payments.sumByResident(pgId)) {
			paid.put((Long) row[0], (BigDecimal) row[1]);
		}
		Map<Long, List<ResidentRentChange>> changesByResident = rentChanges.findByPgIdOrderByEffectiveFrom(pgId).stream()
				.collect(Collectors.groupingBy(ResidentRentChange::getResidentId));

		Map<Long, String> photos = media.residentPhotos(pgId);

		return resList.stream()
				.map(r -> toResponse(r, roomById.get(r.getRoomId()),
						r.getSectionId() != null ? sectionById.get(r.getSectionId()) : null,
						r.getBedId() != null ? bedById.get(r.getBedId()) : null,
						r.getSharingOptionId() != null ? optionById.get(r.getSharingOptionId()) : null,
						paid.getOrDefault(r.getId(), BigDecimal.ZERO),
						changesByResident.getOrDefault(r.getId(), List.of()), photos.get(r.getId())))
				.toList();
	}

	public ResidentSummaryResponse getSummary() {
		return getSummary(TenantContext.requirePgId());
	}

	public ResidentSummaryResponse getSummary(Long pgId) {
		checkAutoCheckouts(pgId);
		long totalResidents = residents.countByPgIdAndStatus(pgId,ResidentStatus.ACTIVE);
		List<Resident> resList = residents.findByPgIdAndStatusOrderByName(pgId, ResidentStatus.ACTIVE);

		Map<Long, Room> roomById = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, Function.identity()));
		Map<Long, BigDecimal> paid = new java.util.HashMap<>();
		for (Object[] row : payments.sumByResident(pgId)) {
			paid.put((Long) row[0], (BigDecimal) row[1]);
		}
		Map<Long, List<ResidentRentChange>> changesByResident = rentChanges.findByPgIdOrderByEffectiveFrom(pgId).stream()
				.collect(Collectors.groupingBy(ResidentRentChange::getResidentId));

		BigDecimal totalRent = BigDecimal.ZERO;
		BigDecimal totalDue = BigDecimal.ZERO;
		long withDues = 0;
		long paidCount = 0;

		for (Resident r : resList) {
			Room room = roomById.get(r.getRoomId());
			List<ResidentRentChange> changes = changesByResident.getOrDefault(r.getId(), List.of());
			totalRent = totalRent.add(billing.currentRent(r, room, changes));
			BigDecimal billed = billing.billed(r, room, changes);
			BigDecimal due = Billing.due(billed, paid.getOrDefault(r.getId(), BigDecimal.ZERO));
			totalDue = totalDue.add(due);
			if (due.compareTo(BigDecimal.ZERO) > 0) {
				withDues++;
			} else {
				paidCount++;
			}
		}

		return new ResidentSummaryResponse(totalResidents, totalRent, totalDue, withDues, paidCount);
	}

	public ResidentResponse get(Long id) {
		Resident r = find(id);
		Room room = rooms.findByPgIdAndId(r.getPgId(), r.getRoomId()).orElse(null);
		RoomSection section = r.getSectionId() != null
				? sections.findByPgIdAndId(r.getPgId(), r.getSectionId()).orElse(null) : null;
		Bed bed = r.getBedId() != null ? beds.findByPgIdAndId(r.getPgId(), r.getBedId()).orElse(null) : null;
		RoomSharingOption opt = r.getSharingOptionId() != null
				? sharingOptions.findByPgIdAndId(r.getPgId(), r.getSharingOptionId()).orElse(null) : null;
		return toResponse(r, room, section, bed, opt, payments.sumForResident(r.getPgId(), r.getId()));
	}

	@Transactional
	public ResidentResponse create(ResidentRequest req) {
		Long pgId = TenantContext.requirePgId();
		String phone = req.phone() == null || req.phone().isBlank() ? null : req.phone().trim();
		if (phone != null && residents.existsByPhoneAndStatus(phone, ResidentStatus.ACTIVE)) {
			throw new BadRequestException("A resident with mobile number " + phone + " already has an active room allocation");
		}

		Room room = lockRoom(pgId, req.roomId());
		if (residents.countByPgIdAndRoomIdAndStatus(pgId, room.getId(), ResidentStatus.ACTIVE) >= room.getCapacity()) {
			throw new BadRequestException("Room " + room.getNumber() + " is full");
		}

		RoomSection requestedSection = null;
		if (req.sectionId() != null) {
			requestedSection = sections.findByPgIdAndId(pgId, req.sectionId())
					.orElseThrow(() -> new BadRequestException("Section not found"));
			if (!requestedSection.getRoomId().equals(room.getId())) {
				throw new BadRequestException("Section does not belong to room " + room.getNumber());
			}
			if (residents.countByPgIdAndSectionIdAndStatus(pgId, requestedSection.getId(), ResidentStatus.ACTIVE) >= requestedSection.getCapacity()) {
				throw new BadRequestException("Section " + requestedSection.getSectionName() + " is full");
			}
		}

		Bed assignedBed = null;
		if (req.bedId() != null) {
			Bed bed = beds.lockByPgIdAndId(pgId, req.bedId())
					.orElseThrow(() -> new BadRequestException("Bed not found"));
			if (!bed.getRoomId().equals(room.getId())) {
				throw new BadRequestException("Bed does not belong to room " + room.getNumber());
			}
			if (req.sectionId() != null && !bed.getSectionId().equals(req.sectionId())) {
				throw new BadRequestException("Bed does not belong to the selected section");
			}
			if (bed.getStatus() == BedStatus.OCCUPIED) {
				throw new BadRequestException("Bed " + bed.getBedNumber() + " is already occupied");
			}
			RoomSection bedSection = sections.findByPgIdAndId(pgId, bed.getSectionId()).orElse(null);
			if (bedSection != null && residents.countByPgIdAndSectionIdAndStatus(pgId, bedSection.getId(), ResidentStatus.ACTIVE) >= bedSection.getCapacity()) {
				throw new BadRequestException("Section " + bedSection.getSectionName() + " is full");
			}
			assignedBed = bed;
		} else if (requestedSection != null) {
			List<Bed> sectionVacantBeds = beds.findByPgIdAndSectionIdAndStatusOrderByBedNumber(pgId, requestedSection.getId(), BedStatus.VACANT);
			if (!sectionVacantBeds.isEmpty()) {
				assignedBed = sectionVacantBeds.get(0);
			} else {
				List<Bed> allSecBeds = beds.findByPgIdAndSectionIdOrderByBedNumber(pgId, requestedSection.getId());
				if (!allSecBeds.isEmpty()) {
					throw new BadRequestException("Section " + requestedSection.getSectionName() + " has no vacant beds available");
				}
			}
		} else {
			List<Bed> vacantBeds = beds.findByPgIdAndRoomIdAndStatusOrderByBedNumber(pgId, room.getId(),
					BedStatus.VACANT);
			if (!vacantBeds.isEmpty()) {
				for (Bed b : vacantBeds) {
					RoomSection sec = sections.findByPgIdAndId(pgId, b.getSectionId()).orElse(null);
					if (sec == null || residents.countByPgIdAndSectionIdAndStatus(pgId, sec.getId(), ResidentStatus.ACTIVE) < sec.getCapacity()) {
						assignedBed = b;
						break;
					}
				}
			}
		}

		Long targetSectionId = assignedBed != null ? assignedBed.getSectionId()
				: (requestedSection != null ? requestedSection.getId() : null);
		RoomSection section = targetSectionId != null
				? (requestedSection != null && requestedSection.getId().equals(targetSectionId) ? requestedSection
						: sections.findByPgIdAndId(pgId, targetSectionId).orElse(null))
				: null;

		RoomSharingOption opt = null;
		if (req.sharingOptionId() != null) {
			opt = sharingOptions.findByPgIdAndId(pgId, req.sharingOptionId()).orElse(null);
		}

		Resident r = new Resident();
		r.setStatus(ResidentStatus.ACTIVE);
		apply(r, req, assignedBed, section, opt, room, false);
		r = residents.save(r);

		if (assignedBed != null) {
			assignedBed.setStatus(BedStatus.OCCUPIED);
			assignedBed.setResidentId(r.getId());
			beds.save(assignedBed);
		}

		createLogin(r, req.aadhaar());

		// Send WhatsApp Welcome Message safely
		try {
			Pg pg = pgs.findById(pgId).orElse(null);
			String pgName = pg != null ? pg.getName() : "PG Management";
			String bedNum = assignedBed != null ? assignedBed.getBedNumber() : null;
			whatsAppService.sendWelcomeMessage(pgId, pgName, r.getName(), r.getPhone(),
					room.getNumber(), bedNum, r.getRentOverride(), r.getSecurityDeposit(), r.getJoinedOn());
		} catch (Exception ex) {
			log.warn("Failed to trigger WhatsApp welcome message for resident {}: {}", r.getId(), ex.getMessage());
		}

		return toResponse(r, room, section, assignedBed, opt, BigDecimal.ZERO);
	}

	/**
	 * Gives the resident a login: username = mobile number, first password = last 4 digits of the Aadhaar number
	 * (the resident must change it at first login). An existing login on that number is linked instead. The Aadhaar
	 * number itself is never stored.
	 */
	private void createLogin(Resident r, String aadhaar) {
		String phone = r.getPhone();
		if (phone == null || phone.length() > 15 || aadhaar == null || !aadhaar.matches("\\d{12}")) {
			return;
		}
		UserEntity existing = users.findByMobile(phone).or(() -> users.findByUsername(phone)).orElse(null);
		if (existing != null) {
			if (existing.getRole() != Role.RESIDENT) {
				throw new BadRequestException("Mobile number " + phone + " already belongs to another account");
			}
			existing.setPgId(r.getPgId());
			existing.setResidentId(r.getId());
			users.save(existing);
			return;
		}
		UserEntity u = new UserEntity();
		u.setUsername(phone);
		u.setMobile(phone);
		u.setRole(Role.RESIDENT);
		u.setStatus(Status.ACTIVE);
		u.setPgId(r.getPgId());
		u.setResidentId(r.getId());
		u.setPassword(encoder.encode(aadhaar.substring(8)));
		u.setMustChangePassword(true);
		users.save(u);
	}

	@Transactional
	public ResidentResponse update(Long id, ResidentRequest req) {
		Long pgId = TenantContext.requirePgId();
		Resident r = find(id);

		String phone = req.phone() == null || req.phone().isBlank() ? null : req.phone().trim();
		if (phone != null && residents.existsByPhoneAndStatusAndIdNot(phone, ResidentStatus.ACTIVE, id)) {
			throw new BadRequestException("A resident with mobile number " + phone + " already has an active room allocation");
		}

		Room room = lockRoom(pgId, req.roomId());
		boolean roomChanged = !room.getId().equals(r.getRoomId());

		if (roomChanged && residents.countByPgIdAndRoomIdAndStatusAndIdNot(pgId, room.getId(), ResidentStatus.ACTIVE, id) >= room.getCapacity()) {
			throw new BadRequestException("Room " + room.getNumber() + " is full");
		}

		RoomSection requestedSection = null;
		if (req.sectionId() != null) {
			requestedSection = sections.findByPgIdAndId(pgId, req.sectionId())
					.orElseThrow(() -> new BadRequestException("Section not found"));
			if (!requestedSection.getRoomId().equals(room.getId())) {
				throw new BadRequestException("Section does not belong to room " + room.getNumber());
			}
			boolean sectionChanged = !req.sectionId().equals(r.getSectionId());
			if (sectionChanged && residents.countByPgIdAndSectionIdAndStatusAndIdNot(pgId, requestedSection.getId(), ResidentStatus.ACTIVE, id) >= requestedSection.getCapacity()) {
				throw new BadRequestException("Section " + requestedSection.getSectionName() + " is full");
			}
		}

		Bed assignedBed = null;
		if (req.bedId() != null && !req.bedId().equals(r.getBedId())) {
			// Release old bed
			if (r.getBedId() != null) {
				beds.findById(r.getBedId()).ifPresent(oldBed -> {
					oldBed.setStatus(BedStatus.VACANT);
					oldBed.setResidentId(null);
					beds.save(oldBed);
				});
			}

			Bed newBed = beds.lockByPgIdAndId(pgId, req.bedId())
					.orElseThrow(() -> new BadRequestException("Bed not found"));
			if (!newBed.getRoomId().equals(room.getId())) {
				throw new BadRequestException("Bed does not belong to room " + room.getNumber());
			}
			if (req.sectionId() != null && !newBed.getSectionId().equals(req.sectionId())) {
				throw new BadRequestException("Bed does not belong to the selected section");
			}
			if (newBed.getStatus() == BedStatus.OCCUPIED && !r.getId().equals(newBed.getResidentId())) {
				throw new BadRequestException("Bed " + newBed.getBedNumber() + " is already occupied");
			}
			RoomSection bedSection = sections.findByPgIdAndId(pgId, newBed.getSectionId()).orElse(null);
			if (bedSection != null && residents.countByPgIdAndSectionIdAndStatusAndIdNot(pgId, bedSection.getId(), ResidentStatus.ACTIVE, id) >= bedSection.getCapacity()) {
				throw new BadRequestException("Section " + bedSection.getSectionName() + " is full");
			}
			newBed.setStatus(BedStatus.OCCUPIED);
			newBed.setResidentId(r.getId());
			assignedBed = beds.save(newBed);
		} else if (!roomChanged && r.getBedId() != null) {
			assignedBed = beds.findById(r.getBedId()).orElse(null);
		} else if (roomChanged) {
			// Room changed without explicit bedId: release old bed and find a vacant bed in the new room
			if (r.getBedId() != null) {
				beds.findById(r.getBedId()).ifPresent(oldBed -> {
					oldBed.setStatus(BedStatus.VACANT);
					oldBed.setResidentId(null);
					beds.save(oldBed);
				});
				r.setBedId(null);
				r.setSectionId(null);
			}
			if (requestedSection != null) {
				List<Bed> sectionVacantBeds = beds.findByPgIdAndSectionIdAndStatusOrderByBedNumber(pgId, requestedSection.getId(), BedStatus.VACANT);
				if (!sectionVacantBeds.isEmpty()) {
					Bed newBed = sectionVacantBeds.get(0);
					newBed.setStatus(BedStatus.OCCUPIED);
					newBed.setResidentId(r.getId());
					assignedBed = beds.save(newBed);
				}
			} else {
				List<Bed> vacantBeds = beds.findByPgIdAndRoomIdAndStatusOrderByBedNumber(pgId, room.getId(),
						BedStatus.VACANT);
				if (!vacantBeds.isEmpty()) {
					Bed newBed = vacantBeds.get(0);
					newBed.setStatus(BedStatus.OCCUPIED);
					newBed.setResidentId(r.getId());
					assignedBed = beds.save(newBed);
				}
			}
		}

		Long targetSectionId = assignedBed != null ? assignedBed.getSectionId()
				: (req.sectionId() != null ? req.sectionId() : (!roomChanged ? r.getSectionId() : null));
		RoomSection section = targetSectionId != null
				? sections.findByPgIdAndId(pgId, targetSectionId).orElse(null) : null;

		RoomSharingOption opt = null;
		if (req.sharingOptionId() != null) {
			opt = sharingOptions.findByPgIdAndId(pgId, req.sharingOptionId()).orElse(null);
		}

		String previousRoomNumber = roomChanged ? rooms.findById(r.getRoomId()).map(Room::getNumber).orElse(null) : null;

		BigDecimal baseBefore = r.getRentOverride();
		BigDecimal currentBefore = billing.currentRent(r, room, null);
		apply(r, req, assignedBed, section, opt, room, roomChanged);
		boolean rentNotChangedByRequest = !roomChanged && baseBefore != null
				&& (req.rentOverride() == null || req.rentOverride().compareTo(currentBefore) == 0);
		if (rentNotChangedByRequest) {
			// Keep the resident's own rent and any rent change scheduled for their next cycle.
			r.setRentOverride(baseBefore);
		} else {
			// Moved, or the rent was set explicitly: a previously scheduled change no longer applies.
			rentChanges.deleteByPgIdAndResidentId(pgId, id);
		}
		r = residents.save(r);

		if (roomChanged && previousRoomNumber != null) {
			try {
				Pg pg = pgs.findById(pgId).orElse(null);
				String pgName = pg != null ? pg.getName() : "PG Management";
				String bedNum = assignedBed != null ? assignedBed.getBedNumber() : null;
				whatsAppService.sendMoveNotification(pgId, pgName, r.getName(), r.getPhone(),
						previousRoomNumber, room.getNumber(), bedNum, r.getRentOverride());
			} catch (Exception ex) {
				log.warn("Failed to trigger WhatsApp move notification for resident {}: {}", r.getId(), ex.getMessage());
			}
		}

		return toResponse(r, room, section, assignedBed, opt, payments.sumForResident(pgId, id));
	}

	@Transactional
	public ResidentResponse move(Long id, MoveResidentRequest req) {
		Resident r = find(id);
		ResidentRequest updateReq = new ResidentRequest(
				r.getName(),
				r.getPhone(),
				req.roomId(),
				req.sectionId(),
				req.bedId(),
				req.sharingOptionId(),
				req.stayType() != null ? req.stayType() : r.getStayType(),
				req.dailyRate() != null ? req.dailyRate() : r.getDailyRate(),
				req.checkOutDate() != null ? req.checkOutDate() : r.getCheckOutDate(),
				r.getCheckInTime(),
				req.checkOutTime() != null ? req.checkOutTime() : r.getCheckOutTime(),
				req.rentOverride(),
				req.securityDeposit() != null ? req.securityDeposit() : r.getSecurityDeposit(),
				r.getJoinedOn(),
				null
		);
		return update(id, updateReq);
	}

	/** Vacates the bed assignment for a resident while keeping resident record intact. */
	@Transactional
	public ResidentResponse vacateBed(Long id) {
		Long pgId = TenantContext.requirePgId();
		Resident r = find(id);

		if (r.getBedId() != null) {
			beds.findByPgIdAndResidentId(pgId, id).ifPresent(oldBed -> {
				oldBed.setStatus(BedStatus.VACANT);
				oldBed.setResidentId(null);
				beds.save(oldBed);
			});
			r.setBedId(null);
			r.setSectionId(null);
			r = residents.save(r);
		}

		Room room = rooms.findByPgIdAndId(pgId, r.getRoomId()).orElse(null);
		RoomSharingOption opt = r.getSharingOptionId() != null
				? sharingOptions.findByPgIdAndId(pgId, r.getSharingOptionId()).orElse(null) : null;
		return toResponse(r, room, null, null, opt, payments.sumForResident(pgId, id));
	}

	/** Vacates / checks out a resident: verifies zero pending dues, releases bed, and marks CHECKED_OUT. */
	@Transactional
	public ResidentResponse vacate(Long id, LocalDate checkOutDate) {
		return vacate(id, checkOutDate, null, null);
	}

	@Transactional
	public ResidentResponse vacate(Long id, LocalDate checkOutDate, java.time.LocalDateTime checkOutTime) {
		return vacate(id, checkOutDate, checkOutTime, null);
	}

	@Transactional
	public ResidentResponse vacate(Long id, LocalDate checkOutDate, java.time.LocalDateTime checkOutTime, BigDecimal returnedDeposit) {
		Long pgId = TenantContext.requirePgId();
		Resident r = find(id);

		if (r.getStatus() == ResidentStatus.CHECKED_OUT) {
			throw new BadRequestException("Resident is already checked out");
		}

		Room room = rooms.findByPgIdAndId(pgId, r.getRoomId()).orElse(null);
		java.time.LocalDateTime outDateTime = checkOutTime != null ? checkOutTime
				: (checkOutDate != null ? checkOutDate.atTime(clock.now().toLocalTime()) : clock.now());
		LocalDate outDate = outDateTime.toLocalDate();

		// Check dues strictly up to checkout date
		BigDecimal billed;
		if (r.getStayType() == StayType.DAILY) {
			int days = billing.daysStayed(r.getCheckInTime(), outDateTime, r.getJoinedOn(), outDate);
			BigDecimal rate = r.getDailyRate() != null && r.getDailyRate().signum() > 0 ? r.getDailyRate()
					: (r.getRentOverride() != null && r.getRentOverride().signum() > 0 ? r.getRentOverride()
					: (room != null && room.getDailyRent() != null && room.getDailyRent().signum() > 0 ? room.getDailyRent()
					: (room != null && room.getRent() != null ? room.getRent().divide(BigDecimal.valueOf(30), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO)));
			billed = rate.multiply(BigDecimal.valueOf(days));
		} else {
			billed = billing.monthlyBilled(r, room, outDate, null);
		}

		BigDecimal paid = payments.sumForResident(pgId, id);
		BigDecimal due = Billing.due(billed, paid);

		if (due.signum() > 0) {
			throw new BadRequestException("Cannot vacate resident with pending dues of ₹" + due.toPlainString()
					+ ". Please collect all dues before vacating.");
		}

		// Release any assigned bed
		if (r.getBedId() != null) {
			beds.findById(r.getBedId()).ifPresent(oldBed -> {
				oldBed.setStatus(BedStatus.VACANT);
				oldBed.setResidentId(null);
				beds.save(oldBed);
			});
			r.setBedId(null);
		}

		r.setCheckOutDate(outDate);
		r.setCheckOutTime(outDateTime);
		r.setStatus(ResidentStatus.CHECKED_OUT);
		r.setReturnedDeposit(returnedDeposit != null ? returnedDeposit : BigDecimal.ZERO);
		clearNotice(r);
		r = residents.save(r);

		RoomSection section = r.getSectionId() != null
				? sections.findByPgIdAndId(pgId, r.getSectionId()).orElse(null) : null;
		RoomSharingOption opt = r.getSharingOptionId() != null
				? sharingOptions.findByPgIdAndId(pgId, r.getSharingOptionId()).orElse(null) : null;
		return toResponse(r, room, section, null, opt, paid);
	}

	/** Resident asks to leave on {@code vacateOn}; at least {@link #NOTICE_DAYS} days ahead. Waits for the owner. */
	@Transactional
	public ResidentResponse requestNotice(Long id, LocalDate vacateOn, String reason) {
		Resident r = find(id);
		if (r.getStatus() != ResidentStatus.ACTIVE) {
			throw new BadRequestException("Only a current resident can give notice");
		}
		if (r.getNoticeStatus() == NoticeStatus.REQUESTED || r.getNoticeStatus() == NoticeStatus.APPROVED) {
			throw new BadRequestException("A notice to vacate is already in place. Cancel it first to change the date.");
		}

		Room room = rooms.findByPgIdAndId(r.getPgId(), r.getRoomId()).orElse(null);
		List<ResidentRentChange> rc = billing.changesFor(r);
		BigDecimal billed = room == null ? BigDecimal.ZERO : billing.billed(r, room, rc);
		BigDecimal paid = payments.sumForResident(r.getPgId(), r.getId());
		BigDecimal due = Billing.due(billed, paid);
		if (due.signum() > 0) {
			throw new BadRequestException("Cannot submit a vacate notice. You have outstanding dues of ₹" + due.toPlainString() + ". Please clear all dues first.");
		}
		if (vacateOn == null) {
			throw new BadRequestException("Choose the date you will leave");
		}
		LocalDate today = clock.today();
		if (vacateOn.isBefore(today.plusDays(NOTICE_DAYS))) {
			throw new BadRequestException("Notice must be at least " + NOTICE_DAYS + " days. Earliest leaving date is "
					+ today.plusDays(NOTICE_DAYS));
		}
		r.setNoticeStatus(NoticeStatus.REQUESTED);
		r.setNoticeGivenOn(today);
		r.setVacateOn(vacateOn);
		r.setNoticeReason(reason != null && !reason.isBlank() ? reason.trim() : null);
		residents.save(r);

		try {
			push.toStaff(r.getPgId(), "New Vacate Request", push.formatResidentDetails(r.getPgId(), r) + " has requested to vacate on " + vacateOn + ".");
			push.toResident(r.getPgId(), r.getId(), "Vacate Request Submitted", "Your request to vacate on " + vacateOn + " has been submitted successfully.");
		} catch (Exception e) {
			log.warn("Failed to send vacate request push notification: {}", e.getMessage());
		}

		return get(id);
	}

	public long pendingNoticeCount() {
		return residents.countByPgIdAndStatusAndNoticeStatus(TenantContext.requirePgId(), ResidentStatus.ACTIVE,
				NoticeStatus.REQUESTED);
	}

	/** Resident (or owner) withdraws a notice that has not been completed. */
	@Transactional
	public ResidentResponse cancelNotice(Long id) {
		Resident r = find(id);
		if (r.getNoticeStatus() == null) {
			throw new BadRequestException("There is no notice to cancel");
		}
		clearNotice(r);
		residents.save(r);

		try {
			push.toStaff(r.getPgId(), "Vacate Notice Cancelled", push.formatResidentDetails(r.getPgId(), r) + " has cancelled their notice to vacate.");
			push.toResident(r.getPgId(), r.getId(), "Vacate Notice Cancelled", "Your notice to vacate has been successfully cancelled.");
		} catch (Exception e) {
			log.warn("Failed to send vacate cancel push notification: {}", e.getMessage());
		}

		return get(id);
	}

	/** Owner accepts or declines a requested notice. Once approved the bed is advertised as available from the date. */
	@Transactional
	public ResidentResponse decideNotice(Long id, boolean approve) {
		return decideNotice(id, approve, null);
	}

	@Transactional
	public ResidentResponse decideNotice(Long id, boolean approve, BigDecimal returnedDeposit) {
		Resident r = find(id);
		if (r.getNoticeStatus() != NoticeStatus.REQUESTED) {
			throw new BadRequestException("This resident has no pending notice request");
		}
		r.setNoticeStatus(approve ? NoticeStatus.APPROVED : NoticeStatus.REJECTED);
		if (approve) {
			r.setReturnedDeposit(returnedDeposit != null ? returnedDeposit : BigDecimal.ZERO);
		}
		residents.save(r);

		try {
			String statusText = approve ? "approved" : "rejected";
			push.toResident(r.getPgId(), r.getId(), "Vacate Request Update", "Your request to vacate on " + r.getVacateOn() + " has been " + statusText + ".");
		} catch (Exception e) {
			log.warn("Failed to send vacate decision push notification: {}", e.getMessage());
		}

		if (approve && r.getVacateOn() != null && !r.getVacateOn().isAfter(clock.today())) {
			autoCheckout(r);
		}

		return get(id);
	}

	@Transactional
	public void autoCheckout(Resident r) {
		if (r.getStatus() == ResidentStatus.CHECKED_OUT) {
			return;
		}
		if (r.getBedId() != null) {
			beds.findById(r.getBedId()).ifPresent(oldBed -> {
				oldBed.setStatus(BedStatus.VACANT);
				oldBed.setResidentId(null);
				beds.save(oldBed);
			});
			r.setBedId(null);
		}
		r.setCheckOutDate(r.getVacateOn() != null ? r.getVacateOn() : clock.today());
		r.setCheckOutTime(r.getCheckOutDate().atTime(12, 0));
		r.setStatus(ResidentStatus.CHECKED_OUT);
		clearNotice(r);
		residents.save(r);
	}

	@Transactional
	public void checkAutoCheckouts(Long pgId) {
		List<Resident> approved = residents.findByPgIdAndStatusAndNoticeStatus(pgId, ResidentStatus.ACTIVE, NoticeStatus.APPROVED);
		LocalDate today = clock.today();
		for (Resident r : approved) {
			if (r.getVacateOn() != null && !r.getVacateOn().isAfter(today)) {
				autoCheckout(r);
			}
		}
	}

	private static void clearNotice(Resident r) {
		r.setNoticeStatus(null);
		r.setNoticeGivenOn(null);
		r.setVacateOn(null);
		r.setNoticeReason(null);
	}

	/** Re-joins an old/ex-resident into a new room and bed allocation. */
	@Transactional
	public ResidentResponse rejoin(Long id, RejoinResidentRequest req) {
		Long pgId = TenantContext.requirePgId();
		Resident r = find(id);

		if (r.getStatus() == ResidentStatus.ACTIVE) {
			throw new BadRequestException("Resident is already active");
		}

		Room room = lockRoom(pgId, req.roomId());
		if (residents.countByPgIdAndRoomIdAndStatus(pgId, room.getId(), ResidentStatus.ACTIVE) >= room.getCapacity()) {
			throw new BadRequestException("Room " + room.getNumber() + " is full");
		}

		RoomSection requestedSection = null;
		if (req.sectionId() != null) {
			requestedSection = sections.findByPgIdAndId(pgId, req.sectionId())
					.orElseThrow(() -> new BadRequestException("Section not found"));
			if (!requestedSection.getRoomId().equals(room.getId())) {
				throw new BadRequestException("Section does not belong to room " + room.getNumber());
			}
			if (residents.countByPgIdAndSectionIdAndStatus(pgId, requestedSection.getId(), ResidentStatus.ACTIVE) >= requestedSection.getCapacity()) {
				throw new BadRequestException("Section " + requestedSection.getSectionName() + " is full");
			}
		}

		Bed assignedBed = null;
		if (req.bedId() != null) {
			Bed bed = beds.lockByPgIdAndId(pgId, req.bedId())
					.orElseThrow(() -> new BadRequestException("Bed not found"));
			if (!bed.getRoomId().equals(room.getId())) {
				throw new BadRequestException("Bed does not belong to room " + room.getNumber());
			}
			if (req.sectionId() != null && !bed.getSectionId().equals(req.sectionId())) {
				throw new BadRequestException("Bed does not belong to the selected section");
			}
			if (bed.getStatus() == BedStatus.OCCUPIED) {
				throw new BadRequestException("Bed " + bed.getBedNumber() + " is already occupied");
			}
			RoomSection bedSection = sections.findByPgIdAndId(pgId, bed.getSectionId()).orElse(null);
			if (bedSection != null && residents.countByPgIdAndSectionIdAndStatus(pgId, bedSection.getId(), ResidentStatus.ACTIVE) >= bedSection.getCapacity()) {
				throw new BadRequestException("Section " + bedSection.getSectionName() + " is full");
			}
			assignedBed = bed;
		} else if (requestedSection != null) {
			List<Bed> sectionVacantBeds = beds.findByPgIdAndSectionIdAndStatusOrderByBedNumber(pgId, requestedSection.getId(), BedStatus.VACANT);
			if (!sectionVacantBeds.isEmpty()) {
				assignedBed = sectionVacantBeds.get(0);
			} else {
				List<Bed> allSecBeds = beds.findByPgIdAndSectionIdOrderByBedNumber(pgId, requestedSection.getId());
				if (!allSecBeds.isEmpty()) {
					throw new BadRequestException("Section " + requestedSection.getSectionName() + " has no vacant beds available");
				}
			}
		} else {
			List<Bed> vacantBeds = beds.findByPgIdAndRoomIdAndStatusOrderByBedNumber(pgId, room.getId(),
					BedStatus.VACANT);
			if (!vacantBeds.isEmpty()) {
				for (Bed b : vacantBeds) {
					RoomSection sec = sections.findByPgIdAndId(pgId, b.getSectionId()).orElse(null);
					if (sec == null || residents.countByPgIdAndSectionIdAndStatus(pgId, sec.getId(), ResidentStatus.ACTIVE) < sec.getCapacity()) {
						assignedBed = b;
						break;
					}
				}
			}
		}

		Long targetSectionId = assignedBed != null ? assignedBed.getSectionId()
				: (requestedSection != null ? requestedSection.getId() : null);
		RoomSection section = targetSectionId != null
				? (requestedSection != null && requestedSection.getId().equals(targetSectionId) ? requestedSection
						: sections.findByPgIdAndId(pgId, targetSectionId).orElse(null))
				: null;

		RoomSharingOption opt = null;
		if (req.sharingOptionId() != null) {
			opt = sharingOptions.findByPgIdAndId(pgId, req.sharingOptionId()).orElse(null);
		}

		rentChanges.deleteByPgIdAndResidentId(pgId, id);
		r.setRoomId(room.getId());
		r.setStatus(ResidentStatus.ACTIVE);
		r.setCheckOutDate(null);
		r.setCheckOutTime(null);
		if (req.checkInTime() != null) {
			r.setCheckInTime(req.checkInTime());
			r.setJoinedOn(req.checkInTime().toLocalDate());
		} else if (req.joinedOn() != null) {
			r.setJoinedOn(req.joinedOn());
			r.setCheckInTime(req.joinedOn().atTime(clock.now().toLocalTime()));
		} else {
			r.setJoinedOn(clock.today());
			r.setCheckInTime(clock.now());
		}
		if (req.stayType() != null) {
			r.setStayType(req.stayType());
		}
		if (req.dailyRate() != null) {
			r.setDailyRate(req.dailyRate());
		}
		if (assignedBed != null) {
			r.setBedId(assignedBed.getId());
			r.setSectionId(assignedBed.getSectionId());
			assignedBed.setStatus(BedStatus.OCCUPIED);
			assignedBed.setResidentId(r.getId());
			beds.save(assignedBed);
		} else if (req.sectionId() != null) {
			r.setSectionId(req.sectionId());
			r.setBedId(null);
		} else {
			r.setBedId(null);
			r.setSectionId(null);
		}

		if (req.sharingOptionId() != null) {
			r.setSharingOptionId(req.sharingOptionId());
		}
		if (req.rentOverride() != null) {
			r.setRentOverride(req.rentOverride());
		} else if (assignedBed != null && assignedBed.getCustomRentOverride() != null) {
			r.setRentOverride(assignedBed.getCustomRentOverride());
		} else if (section != null && section.getRentPerBed() != null && section.getRentPerBed().signum() > 0) {
			r.setRentOverride(section.getRentPerBed());
		} else if (opt != null && opt.getRentPerBed() != null) {
			r.setRentOverride(opt.getRentPerBed());
		} else {
			r.setRentOverride(room.getRent());
		}

		if (req.securityDeposit() != null) {
			r.setSecurityDeposit(req.securityDeposit());
		} else if (section != null && section.getDepositPerBed() != null && section.getDepositPerBed().signum() > 0) {
			r.setSecurityDeposit(section.getDepositPerBed());
		}

		r = residents.save(r);

		// Send welcome notification
		try {
			Pg pg = pgs.findById(pgId).orElse(null);
			String pgName = pg != null ? pg.getName() : "PG Management";
			String bedNum = assignedBed != null ? assignedBed.getBedNumber() : null;
			whatsAppService.sendWelcomeMessage(pgId, pgName, r.getName(), r.getPhone(),
					room.getNumber(), bedNum, r.getRentOverride(), r.getSecurityDeposit(), r.getJoinedOn());
		} catch (Exception ex) {
			log.warn("Failed to trigger WhatsApp welcome message for rejoining resident {}: {}", r.getId(), ex.getMessage());
		}

		return toResponse(r, room, section, assignedBed, opt, payments.sumForResident(pgId, id));
	}

	/** Removes the resident, their payment history, their bed assignment and their login. */
	@Transactional
	public void delete(Long id) {
		Long pgId = TenantContext.requirePgId();
		Resident r = find(id);

		// Release any assigned bed
		beds.findByPgIdAndResidentId(pgId, id).ifPresent(bed -> {
			bed.setStatus(BedStatus.VACANT);
			bed.setResidentId(null);
			beds.save(bed);
		});

		payments.deleteByPgIdAndResidentId(pgId, id);
		rentChanges.deleteByPgIdAndResidentId(pgId, id);
		users.findByPgIdAndResidentId(pgId, id).ifPresent(users::delete);
		residents.delete(r);
		media.removeAll("RESIDENT", pgId, id);
	}

	private Resident find(Long id) {
		return residents.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Resident not found"));
	}

	private Room lockRoom(Long pgId, Long roomId) {
		return rooms.lockByPgIdAndId(pgId, roomId).orElseThrow(() -> new BadRequestException("Room not found"));
	}

	private void apply(Resident r, ResidentRequest req, Bed assignedBed, RoomSection section, RoomSharingOption opt, Room room, boolean roomChanged) {
		r.setName(req.name().trim());
		r.setPhone(req.phone() == null || req.phone().isBlank() ? null : req.phone());
		r.setRoomId(req.roomId());

		if (req.stayType() != null) {
			r.setStayType(req.stayType());
		} else if (r.getStayType() == null) {
			r.setStayType(StayType.MONTHLY);
		}

		if (req.dailyRate() != null) {
			r.setDailyRate(req.dailyRate());
		} else if (r.getStayType() == StayType.DAILY && r.getDailyRate() == null) {
			r.setDailyRate(room.getDailyRent() != null && room.getDailyRent().signum() > 0 ? room.getDailyRent()
					: (room.getRent() != null ? room.getRent().divide(BigDecimal.valueOf(30), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO));
		}

		if (req.checkInTime() != null) {
			r.setCheckInTime(req.checkInTime());
			r.setJoinedOn(req.checkInTime().toLocalDate());
		} else if (req.joinedOn() != null) {
			r.setJoinedOn(req.joinedOn());
			if (r.getCheckInTime() == null || !r.getCheckInTime().toLocalDate().equals(req.joinedOn())) {
				r.setCheckInTime(req.joinedOn().atTime(clock.now().toLocalTime()));
			}
		} else if (r.getJoinedOn() == null) {
			r.setJoinedOn(clock.today());
			r.setCheckInTime(clock.now());
		}

		if (req.checkOutTime() != null) {
			r.setCheckOutTime(req.checkOutTime());
			r.setCheckOutDate(req.checkOutTime().toLocalDate());
		} else if (req.checkOutDate() != null) {
			r.setCheckOutDate(req.checkOutDate());
			if (r.getCheckOutTime() == null || !r.getCheckOutTime().toLocalDate().equals(req.checkOutDate())) {
				r.setCheckOutTime(req.checkOutDate().atTime(clock.now().toLocalTime()));
			}
		}

		if (assignedBed != null) {
			r.setBedId(assignedBed.getId());
			r.setSectionId(assignedBed.getSectionId());
		} else if (req.sectionId() != null) {
			r.setSectionId(req.sectionId());
			r.setBedId(null);
		} else if (roomChanged) {
			r.setBedId(null);
			r.setSectionId(null);
		}

		if (req.sharingOptionId() != null) {
			r.setSharingOptionId(req.sharingOptionId());
		} else if (roomChanged) {
			r.setSharingOptionId(null);
		}

		if (req.rentOverride() != null) {
			r.setRentOverride(req.rentOverride());
		} else if (assignedBed != null && assignedBed.getCustomRentOverride() != null) {
			r.setRentOverride(assignedBed.getCustomRentOverride());
		} else if (section != null && section.getRentPerBed() != null && section.getRentPerBed().signum() > 0) {
			r.setRentOverride(section.getRentPerBed());
		} else if (opt != null && opt.getRentPerBed() != null) {
			r.setRentOverride(opt.getRentPerBed());
		} else if (r.getRentOverride() == null || roomChanged) {
			r.setRentOverride(room.getRent());
		}

		if (req.securityDeposit() != null) {
			r.setSecurityDeposit(req.securityDeposit());
		} else if (section != null && section.getDepositPerBed() != null && section.getDepositPerBed().signum() > 0) {
			r.setSecurityDeposit(section.getDepositPerBed());
		} else if (opt != null && opt.getDepositPerBed() != null) {
			r.setSecurityDeposit(opt.getDepositPerBed());
		}
	}

	private ResidentResponse toResponse(Resident r, Room room, RoomSection section, Bed bed, RoomSharingOption opt, BigDecimal paid) {
		return toResponse(r, room, section, bed, opt, paid, null, media.residentPhoto(r.getPgId(), r.getId()));
	}

	/** {@code changes} = the resident's scheduled rent changes, ascending; null loads them. */
	private ResidentResponse toResponse(Resident r, Room room, RoomSection section, Bed bed, RoomSharingOption opt, BigDecimal paid,
			List<ResidentRentChange> changes, String photoUrl) {
		List<ResidentRentChange> rc = changes != null ? changes : billing.changesFor(r);
		BigDecimal baseRent = r.getRentOverride() != null ? r.getRentOverride()
				: (opt != null ? opt.getRentPerBed() : (room == null ? BigDecimal.ZERO : room.getRent()));
		BigDecimal rent = r.getStayType() == StayType.DAILY ? baseRent : currentRent(r, baseRent, rc);
		BigDecimal billed = room == null ? BigDecimal.ZERO : billing.billed(r, room, rc);
		BigDecimal deposit = r.getSecurityDeposit() != null ? r.getSecurityDeposit()
				: (section != null && section.getDepositPerBed() != null ? section.getDepositPerBed() : BigDecimal.ZERO);
		int daysStayed = r.getStayType() == StayType.DAILY
				? billing.daysStayed(r.getCheckInTime(), r.getCheckOutTime(), r.getJoinedOn(), r.getCheckOutDate()) : 0;
		Double hoursStayed = r.getStayType() == StayType.DAILY
				? billing.hoursStayed(r.getCheckInTime(), r.getCheckOutTime(), r.getJoinedOn(), r.getCheckOutDate()) : null;
		return new ResidentResponse(r.getId(), r.getName(), r.getPhone(), r.getRoomId(),
				room == null ? null : room.getNumber(),
				r.getSectionId(), section == null ? null : section.getSectionName(),
				r.getBedId(), bed == null ? null : bed.getBedNumber(),
				r.getSharingOptionId(), opt == null ? null : opt.getSharingType(),
				r.getStayType(), r.getDailyRate(),
				r.getCheckOutDate(), r.getCheckInTime(), r.getCheckOutTime(),
				daysStayed, hoursStayed,
				r.getJoinedOn(), rent, baseRent,
				rc.stream().map(c -> new RentChangeInfo(c.getEffectiveFrom(), c.getRent())).toList(),
				deposit, r.getReturnedDeposit() != null ? r.getReturnedDeposit() : BigDecimal.ZERO, billed, paid, Billing.due(billed, paid),
				r.getStatus(), r.getNoticeStatus(), r.getNoticeGivenOn(), r.getVacateOn(), r.getNoticeReason(),
				photoUrl, r.getAttachmentUrl());
	}

	private BigDecimal currentRent(Resident r, BigDecimal baseRent, List<ResidentRentChange> rc) {
		if (r.getJoinedOn() == null) {
			return baseRent;
		}
		LocalDate today = clock.today();
		long elapsed = today.isBefore(r.getJoinedOn()) ? 0 : java.time.temporal.ChronoUnit.MONTHS.between(r.getJoinedOn(), today);
		return Billing.rentForCycle(baseRent, rc, r.getJoinedOn().plusMonths(elapsed));
	}
}
