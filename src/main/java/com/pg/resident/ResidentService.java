package com.pg.resident;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.billing.Billing;
import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.notification.WhatsAppService;
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
			BigDecimal rentOverride, BigDecimal securityDeposit, LocalDate joinedOn) {
	}

	public record MoveResidentRequest(@NotNull Long roomId, Long sectionId, Long bedId, Long sharingOptionId,
			StayType stayType, BigDecimal dailyRate, LocalDate checkOutDate, java.time.LocalDateTime checkOutTime,
			BigDecimal rentOverride, BigDecimal securityDeposit) {
	}

	public record RejoinResidentRequest(@NotNull Long roomId, Long sectionId, Long bedId, Long sharingOptionId,
			StayType stayType, BigDecimal dailyRate, java.time.LocalDateTime checkInTime,
			BigDecimal rentOverride, BigDecimal securityDeposit, LocalDate joinedOn) {
	}

	public record ResidentResponse(Long id, String name, String phone, Long roomId, String roomNumber,
			Long sectionId, String sectionName, Long bedId, String bedNumber,
			Long sharingOptionId, SharingType sharingType, StayType stayType, BigDecimal dailyRate,
			LocalDate checkOutDate, java.time.LocalDateTime checkInTime, java.time.LocalDateTime checkOutTime,
			int daysStayed, Double hoursStayed,
			LocalDate joinedOn, BigDecimal rent, BigDecimal securityDeposit, BigDecimal billed, BigDecimal paid, BigDecimal due,
			ResidentStatus status) {
	}

	private final ResidentRepository residents;
	private final RoomRepository rooms;
	private final RoomSectionRepository sections;
	private final BedRepository beds;
	private final RoomSharingOptionRepository sharingOptions;
	private final PaymentRepository payments;
	private final UserRepository users;
	private final PgRepository pgs;
	private final Billing billing;
	private final WhatsAppService whatsAppService;
	private final AppClock clock;

	public ResidentService(ResidentRepository residents, RoomRepository rooms, RoomSectionRepository sections,
			BedRepository beds, RoomSharingOptionRepository sharingOptions, PaymentRepository payments,
			UserRepository users, PgRepository pgs, Billing billing, WhatsAppService whatsAppService, AppClock clock) {
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
	}

	public List<ResidentResponse> list() {
		return list("ACTIVE");
	}

	public List<ResidentResponse> list(String statusFilter) {
		Long pgId = TenantContext.requirePgId();
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
						paid.getOrDefault(r.getId(), BigDecimal.ZERO)))
				.toList();
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

		apply(r, req, assignedBed, section, opt, room, roomChanged);
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
				r.getJoinedOn()
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
		return vacate(id, checkOutDate, null);
	}

	@Transactional
	public ResidentResponse vacate(Long id, LocalDate checkOutDate, java.time.LocalDateTime checkOutTime) {
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
		int months = billing.monthsBilled(r.getJoinedOn(), outDate);
		BigDecimal billed;
		if (r.getStayType() == StayType.DAILY) {
			int days = billing.daysStayed(r.getCheckInTime(), outDateTime, r.getJoinedOn(), outDate);
			BigDecimal rate = r.getDailyRate() != null && r.getDailyRate().signum() > 0 ? r.getDailyRate()
					: (r.getRentOverride() != null && r.getRentOverride().signum() > 0 ? r.getRentOverride()
					: (room != null && room.getDailyRent() != null && room.getDailyRent().signum() > 0 ? room.getDailyRent()
					: (room != null && room.getRent() != null ? room.getRent().divide(BigDecimal.valueOf(30), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO)));
			billed = rate.multiply(BigDecimal.valueOf(days));
		} else {
			BigDecimal rent = r.getRentOverride() != null ? r.getRentOverride()
					: (room != null ? room.getRent() : BigDecimal.ZERO);
			billed = rent.multiply(BigDecimal.valueOf(months));
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
		r = residents.save(r);

		RoomSection section = r.getSectionId() != null
				? sections.findByPgIdAndId(pgId, r.getSectionId()).orElse(null) : null;
		RoomSharingOption opt = r.getSharingOptionId() != null
				? sharingOptions.findByPgIdAndId(pgId, r.getSharingOptionId()).orElse(null) : null;
		return toResponse(r, room, section, null, opt, paid);
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
		users.findByPgIdAndResidentId(pgId, id).ifPresent(users::delete);
		residents.delete(r);
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
		BigDecimal rent = r.getRentOverride() != null ? r.getRentOverride()
				: (opt != null ? opt.getRentPerBed() : (room == null ? BigDecimal.ZERO : room.getRent()));
		BigDecimal billed = room == null ? BigDecimal.ZERO : billing.billed(r, room);
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
				r.getJoinedOn(), rent, deposit, billed, paid, Billing.due(billed, paid),
				r.getStatus());
	}
}
