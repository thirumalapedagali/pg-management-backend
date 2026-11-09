package com.pg.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.push.PushService;
import com.pg.resident.ResidentService;
import com.pg.resident.ResidentService.ResidentRequest;
import com.pg.resident.ResidentService.ResidentResponse;
import com.pg.resident.StayType;
import com.pg.room.Bed;
import com.pg.room.BedRepository;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.room.RoomSection;
import com.pg.room.RoomSectionRepository;
import com.pg.tenant.TenantContext;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

@Service
public class BookingRequestService {

	private static final Logger log = LoggerFactory.getLogger(BookingRequestService.class);

	public record BookingRequestResponse(
			Long id,
			Long pgId,
			Long roomId,
			String roomNumber,
			Long sectionId,
			String sectionName,
			Long bedId,
			String bedNumber,
			String name,
			String mobile,
			String email,
			LocalDate expectedJoinDate,
			String message,
			BigDecimal rent,
			BigDecimal deposit,
			BookingRequestStatus status,
			String rejectionReason,
			Long residentId,
			LocalDateTime createdAt
	) {}

	public record ApproveBookingRequest(
			String name,
			String phone,
			Long roomId,
			Long sectionId,
			Long bedId,
			BigDecimal rent,
			BigDecimal securityDeposit,
			LocalDate joinedOn,
			String aadhaar
	) {}

	public record RejectBookingRequest(
			String reason
	) {}

	private final BookingRequestRepository bookingRequests;
	private final PgRepository pgs;
	private final RoomRepository rooms;
	private final RoomSectionRepository sections;
	private final BedRepository beds;
	private final ResidentService residentService;
	private final UserRepository users;
	private final PushService push;

	public BookingRequestService(
			BookingRequestRepository bookingRequests,
			PgRepository pgs,
			RoomRepository rooms,
			RoomSectionRepository sections,
			BedRepository beds,
			ResidentService residentService,
			UserRepository users,
			PushService push
	) {
		this.bookingRequests = bookingRequests;
		this.pgs = pgs;
		this.rooms = rooms;
		this.sections = sections;
		this.beds = beds;
		this.residentService = residentService;
		this.users = users;
		this.push = push;
	}

	@Transactional
	public BookingRequest createInquiry(Long pgId, Long roomId, Long sectionId, Long bedId,
			String name, String mobile, String email, LocalDate expectedJoinDate, String message) {
		Pg pg = pgs.findById(pgId).orElseThrow(() -> new NotFoundException("PG not found"));

		BookingRequest br = new BookingRequest();
		br.setPgId(pg.getId());
		br.setName(name.trim());
		br.setMobile(mobile.trim());
		br.setEmail(email != null && !email.isBlank() ? email.trim() : null);
		br.setExpectedJoinDate(expectedJoinDate != null ? expectedJoinDate : LocalDate.now());
		br.setMessage(message != null && !message.isBlank() ? message.trim() : null);
		br.setStatus(BookingRequestStatus.PENDING);

		if (roomId != null) {
			rooms.findByPgIdAndId(pgId, roomId).ifPresent(r -> {
				br.setRoomId(r.getId());
				br.setRoomNumber(r.getNumber());
				if (br.getRent() == null) br.setRent(r.getRent());
			});
		}

		if (sectionId != null) {
			sections.findByPgIdAndId(pgId, sectionId).ifPresent(sec -> {
				br.setSectionId(sec.getId());
				br.setSectionName(sec.getSectionName());
				if (sec.getRentPerBed() != null) br.setRent(sec.getRentPerBed());
				if (sec.getDepositPerBed() != null) br.setDeposit(sec.getDepositPerBed());
			});
		}

		if (bedId != null) {
			beds.findByPgIdAndId(pgId, bedId).ifPresent(b -> {
				br.setBedId(b.getId());
				br.setBedNumber(b.getBedNumber());
				if (b.getCustomRentOverride() != null) br.setRent(b.getCustomRentOverride());
				if (br.getRoomId() == null) {
					br.setRoomId(b.getRoomId());
					rooms.findByPgIdAndId(pgId, b.getRoomId()).ifPresent(r -> br.setRoomNumber(r.getNumber()));
				}
				if (br.getSectionId() == null && b.getSectionId() != null) {
					br.setSectionId(b.getSectionId());
					sections.findByPgIdAndId(pgId, b.getSectionId()).ifPresent(s -> {
						br.setSectionName(s.getSectionName());
						if (br.getDeposit() == null && s.getDepositPerBed() != null) {
							br.setDeposit(s.getDepositPerBed());
						}
					});
				}
			});
		}

		BookingRequest saved = bookingRequests.save(br);
		try {
			push.toStaff(pgId, "New Check-In Request", "A new check-in request has been received from " + name.trim() + ".");
			push.toUserByMobile(pgId, mobile.trim(), "Check-In Request Received", "Your check-in request for " + pg.getName() + " has been received! The manager will review it soon.");
		} catch (Exception e) {
			log.warn("Failed to send check-in push notification: {}", e.getMessage());
		}
		return saved;
	}

	public List<BookingRequestResponse> list(String statusFilter) {
		Long pgId = TenantContext.requirePgId();
		List<BookingRequest> list;
		if (statusFilter == null || statusFilter.isBlank() || "ALL".equalsIgnoreCase(statusFilter)) {
			list = bookingRequests.findByPgIdOrderByCreatedAtDesc(pgId);
		} else {
			try {
				BookingRequestStatus status = BookingRequestStatus.valueOf(statusFilter.toUpperCase());
				list = bookingRequests.findByPgIdAndStatusOrderByCreatedAtDesc(pgId, status);
			} catch (IllegalArgumentException e) {
				list = bookingRequests.findByPgIdOrderByCreatedAtDesc(pgId);
			}
		}
		return list.stream().map(this::toResponse).toList();
	}

	public long countPending() {
		Long pgId = TenantContext.requirePgId();
		return bookingRequests.countByPgIdAndStatus(pgId, BookingRequestStatus.PENDING);
	}

	@Transactional
	public ResidentResponse approve(Long id, ApproveBookingRequest req) {
		Long pgId = TenantContext.requirePgId();
		BookingRequest br = bookingRequests.findByPgIdAndId(pgId, id)
				.orElseThrow(() -> new NotFoundException("Booking request not found"));

		if (br.getStatus() != BookingRequestStatus.PENDING) {
			throw new BadRequestException("This booking request has already been " + br.getStatus().name().toLowerCase());
		}

		String resName = req != null && req.name() != null && !req.name().isBlank() ? req.name().trim() : br.getName();
		String resPhone = req != null && req.phone() != null && !req.phone().isBlank() ? req.phone().trim() : br.getMobile();
		Long targetRoomId = req != null && req.roomId() != null ? req.roomId() : br.getRoomId();
		Long targetSectionId = req != null && req.sectionId() != null ? req.sectionId() : br.getSectionId();
		Long targetBedId = req != null && req.bedId() != null ? req.bedId() : br.getBedId();
		BigDecimal targetRent = req != null && req.rent() != null ? req.rent() : br.getRent();
		BigDecimal targetDeposit = req != null && req.securityDeposit() != null ? req.securityDeposit() : br.getDeposit();
		LocalDate targetJoinedOn = req != null && req.joinedOn() != null ? req.joinedOn()
				: (br.getExpectedJoinDate() != null ? br.getExpectedJoinDate() : LocalDate.now());

		if (targetRoomId == null) {
			throw new BadRequestException("Please select a room to assign to this resident");
		}

		ResidentRequest residentReq = new ResidentRequest(
				resName,
				resPhone,
				targetRoomId,
				targetSectionId,
				targetBedId,
				null,
				StayType.MONTHLY,
				null,
				null,
				null,
				null,
				targetRent,
				targetDeposit,
				targetJoinedOn,
				req != null ? req.aadhaar() : null
		);

		ResidentResponse createdResident = residentService.create(residentReq);

		br.setStatus(BookingRequestStatus.APPROVED);
		br.setResidentId(createdResident.id());
		bookingRequests.save(br);

		try {
			Pg pg = pgs.findById(pgId).orElse(null);
			String pgName = pg != null ? pg.getName() : "PG Management";
			push.toResident(pgId, createdResident.id(), "Check-In Approved", "Congratulations! Your check-in request for " + pgName + " has been approved!");
		} catch (Exception e) {
			log.warn("Failed to send check-in approval push notification: {}", e.getMessage());
		}

		// Link existing user account (if resident registered in the mobile app)
		try {
			users.findByMobile(resPhone).or(() -> users.findByUsername(resPhone)).ifPresent(u -> {
				u.setPgId(pgId);
				u.setResidentId(createdResident.id());
				users.save(u);
				log.info("Linked UserEntity {} to resident {} at PG {}", u.getUsername(), createdResident.id(), pgId);
			});
		} catch (Exception e) {
			log.warn("Failed to link registered user for phone {}: {}", resPhone, e.getMessage());
		}

		return createdResident;
	}

	@Transactional
	public BookingRequestResponse reject(Long id, RejectBookingRequest req) {
		Long pgId = TenantContext.requirePgId();
		BookingRequest br = bookingRequests.findByPgIdAndId(pgId, id)
				.orElseThrow(() -> new NotFoundException("Booking request not found"));

		if (br.getStatus() != BookingRequestStatus.PENDING) {
			throw new BadRequestException("This booking request has already been " + br.getStatus().name().toLowerCase());
		}

		br.setStatus(BookingRequestStatus.REJECTED);
		if (req != null && req.reason() != null && !req.reason().isBlank()) {
			br.setRejectionReason(req.reason().trim());
		}
		BookingRequest saved = bookingRequests.save(br);
		try {
			Pg pg = pgs.findById(pgId).orElse(null);
			String pgName = pg != null ? pg.getName() : "PG Management";
			String reasonText = (req != null && req.reason() != null && !req.reason().isBlank()) ? " Reason: " + req.reason().trim() : "";
			push.toUserByMobile(pgId, br.getMobile(), "Check-In Request Update", "Your check-in request for " + pgName + " has been reviewed. Status: Rejected." + reasonText);
		} catch (Exception e) {
			log.warn("Failed to send check-in rejection push notification: {}", e.getMessage());
		}
		return toResponse(saved);
	}

	private BookingRequestResponse toResponse(BookingRequest br) {
		return new BookingRequestResponse(
				br.getId(),
				br.getPgId(),
				br.getRoomId(),
				br.getRoomNumber(),
				br.getSectionId(),
				br.getSectionName(),
				br.getBedId(),
				br.getBedNumber(),
				br.getName(),
				br.getMobile(),
				br.getEmail(),
				br.getExpectedJoinDate(),
				br.getMessage(),
				br.getRent(),
				br.getDeposit(),
				br.getStatus(),
				br.getRejectionReason(),
				br.getResidentId(),
				br.getCreatedAt()
		);
	}
}
