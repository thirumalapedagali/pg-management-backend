package com.pg.publicapi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.NotFoundException;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.resident.ResidentRepository;
import com.pg.room.AcType;
import com.pg.room.Bed;
import com.pg.room.BedRepository;
import com.pg.room.BedStatus;
import com.pg.room.BedType;
import com.pg.room.GenderPolicy;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.room.RoomSection;
import com.pg.room.RoomSectionRepository;
import com.pg.room.RoomSharingOption;
import com.pg.room.RoomSharingOptionRepository;
import com.pg.room.RoomType;
import com.pg.room.SharingType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/public")
public class PublicPgController {

	public record PublicPgSummaryResponse(
			Long id,
			String name,
			String ownerName,
			String mobile,
			String email,
			String address,
			String city,
			String state,
			Integer totalFloors,
			String stayType,
			String unitLabel,
			String locationUrl,
			String genderType,
			String upiId,
			String upiName,
			String description,
			List<String> amenities,
			BigDecimal startingPrice,
			int totalRooms,
			int totalBeds,
			int vacantBeds
	) {
	}

	public record PublicBedDetail(
			Long id,
			String bedNumber,
			BedType bedType,
			BigDecimal rent,
			BedStatus status
	) {
	}

	public record PublicSectionDetail(
			Long id,
			String sectionName,
			SharingType sharingType,
			int capacity,
			BigDecimal rentPerBed,
			BigDecimal depositPerBed,
			AcType acType,
			boolean attachedBathroom,
			List<PublicBedDetail> vacantBeds
	) {
	}

	public record PublicSharingOption(
			Long id,
			SharingType sharingType,
			int capacity,
			BigDecimal rentPerBed,
			BigDecimal depositPerBed
	) {
	}

	public record PublicRoomDetail(
			Long id,
			String number,
			String roomName,
			String buildingName,
			String floorName,
			RoomType roomType,
			AcType acType,
			boolean attachedBathroom,
			boolean balcony,
			GenderPolicy genderPolicy,
			int capacity,
			BigDecimal rent,
			BigDecimal dailyRent,
			int vacantBedsCount,
			List<PublicSharingOption> sharingOptions,
			List<PublicSectionDetail> sections,
			List<PublicBedDetail> allVacantBeds
	) {
	}

	public record PublicPgDetailResponse(
			Long id,
			String name,
			String ownerName,
			String mobile,
			String email,
			String address,
			String city,
			String state,
			Integer totalFloors,
			String stayType,
			String unitLabel,
			String locationUrl,
			String genderType,
			String upiId,
			String upiName,
			String description,
			List<String> amenities,
			BigDecimal startingPrice,
			int totalRooms,
			int totalBeds,
			int totalVacantBeds,
			List<PublicRoomDetail> rooms
	) {
	}

	public record BookingInquiryRequest(
			@NotNull Long pgId,
			Long roomId,
			Long sectionId,
			Long bedId,
			@NotBlank String name,
			@NotBlank String mobile,
			String email,
			String stayType,
			String expectedJoinDate,
			String message
	) {
	}

	public record BookingInquiryResponse(
			boolean success,
			String message
	) {
	}

	private static final List<String> DEFAULT_PG_AMENITIES = List.of(
			"Wi-Fi", "3 Times Homely Food", "AC / Air Conditioned", "24/7 Power Backup",
			"Hot Water Geyser", "Daily Housekeeping", "Washing Machine / Laundry",
			"24/7 CCTV Security", "2-Wheeler / 4-Wheeler Parking", "RO Drinking Water",
			"Refrigerator", "Smart TV / Lounge"
	);

	private final PgRepository pgs;
	private final RoomRepository rooms;
	private final RoomSectionRepository sections;
	private final BedRepository beds;
	private final RoomSharingOptionRepository sharingOptions;
	private final ResidentRepository residents;
	private final com.pg.booking.BookingRequestService bookingRequestService;

	public PublicPgController(PgRepository pgs, RoomRepository rooms, RoomSectionRepository sections,
			BedRepository beds, RoomSharingOptionRepository sharingOptions, ResidentRepository residents,
			com.pg.booking.BookingRequestService bookingRequestService) {
		this.pgs = pgs;
		this.rooms = rooms;
		this.sections = sections;
		this.beds = beds;
		this.sharingOptions = sharingOptions;
		this.residents = residents;
		this.bookingRequestService = bookingRequestService;
	}

	@GetMapping("/pgs")
	public List<PublicPgSummaryResponse> listPgs(@RequestParam(required = false) String city) {
		List<Pg> allPgs = pgs.findAll().stream().filter(Pg::isActive).toList();
		if (city != null && !city.isBlank()) {
			String q = city.trim().toLowerCase();
			allPgs = allPgs.stream().filter(p -> p.getCity() != null && p.getCity().toLowerCase().contains(q)).toList();
		}

		List<PublicPgSummaryResponse> result = new ArrayList<>();
		for (Pg pg : allPgs) {
			List<Room> pgRooms = rooms.findByPgIdOrderByNumber(pg.getId());
			List<Bed> pgBeds = beds.findByPgIdOrderByBedNumber(pg.getId());
			int totalBeds = pgBeds.size();
			int vacantBeds = (int) pgBeds.stream().filter(b -> b.getStatus() == BedStatus.VACANT).count();

			// Calculate lowest price from vacant beds or rooms
			BigDecimal minRent = pg.getStartingPrice();
			if (minRent == null || minRent.compareTo(BigDecimal.ZERO) <= 0) {
				for (Bed b : pgBeds) {
					if (b.getCustomRentOverride() != null && b.getCustomRentOverride().compareTo(BigDecimal.ZERO) > 0) {
						if (minRent == null || b.getCustomRentOverride().compareTo(minRent) < 0) {
							minRent = b.getCustomRentOverride();
						}
					}
				}
				if (minRent == null) {
					for (Room r : pgRooms) {
						if (r.getRent() != null && r.getRent().compareTo(BigDecimal.ZERO) > 0) {
							if (minRent == null || r.getRent().compareTo(minRent) < 0) {
								minRent = r.getRent();
							}
						}
					}
				}
			}

			List<String> amenitiesList = parseAmenities(pg.getAmenities());
			result.add(new PublicPgSummaryResponse(
					pg.getId(),
					pg.getName(),
					pg.getOwnerName(),
					pg.getMobile(),
					pg.getEmail(),
					pg.getAddress(),
					pg.getCity(),
					pg.getState(),
					pg.getTotalFloors(),
					pg.getStayType() != null ? pg.getStayType() : "PG",
					pg.getUnitLabel() != null ? pg.getUnitLabel() : "Room",
					pg.getLocationUrl(),
					pg.getGenderType() != null ? pg.getGenderType() : "CO_LIVING",
					pg.getUpiId(),
					pg.getUpiName(),
					pg.getDescription(),
					amenitiesList,
					minRent != null ? minRent : BigDecimal.valueOf(5000),
					pgRooms.size(),
					totalBeds,
					vacantBeds
			));
		}
		return result;
	}

	@GetMapping("/pgs/{id}")
	public PublicPgDetailResponse getPgDetails(@PathVariable Long id) {
		Pg pg = pgs.findById(id).orElseThrow(() -> new NotFoundException("PG not found"));

		List<Room> roomList = rooms.findByPgIdOrderByNumber(id);
		List<RoomSection> sectionList = sections.findByPgIdOrderBySectionName(id);
		List<Bed> bedList = beds.findByPgIdOrderByBedNumber(id);
		List<RoomSharingOption> optionList = sharingOptions.findByPgIdOrderByCapacity(id);

		Map<Long, List<Bed>> bedsBySection = new HashMap<>();
		for (Bed b : bedList) {
			bedsBySection.computeIfAbsent(b.getSectionId(), k -> new ArrayList<>()).add(b);
		}

		Map<Long, List<RoomSection>> sectionsByRoom = new HashMap<>();
		for (RoomSection s : sectionList) {
			sectionsByRoom.computeIfAbsent(s.getRoomId(), k -> new ArrayList<>()).add(s);
		}

		Map<Long, List<RoomSharingOption>> optionsByRoom = new HashMap<>();
		for (RoomSharingOption opt : optionList) {
			optionsByRoom.computeIfAbsent(opt.getRoomId(), k -> new ArrayList<>()).add(opt);
		}

		int totalBeds = bedList.size();
		int totalVacant = 0;
		List<PublicRoomDetail> roomDetails = new ArrayList<>();
		BigDecimal minRent = pg.getStartingPrice();

		for (Room r : roomList) {
			List<RoomSection> roomSections = sectionsByRoom.getOrDefault(r.getId(), List.of());
			List<RoomSharingOption> roomOptions = optionsByRoom.getOrDefault(r.getId(), List.of());

			List<PublicSharingOption> sharingResponses = roomOptions.stream()
					.filter(RoomSharingOption::isActive)
					.map(opt -> new PublicSharingOption(opt.getId(), opt.getSharingType(), opt.getCapacity(),
							opt.getRentPerBed(), opt.getDepositPerBed()))
					.toList();

			List<PublicSectionDetail> sectionResponses = new ArrayList<>();
			List<PublicBedDetail> allRoomVacantBeds = new ArrayList<>();

			for (RoomSection sec : roomSections) {
				List<Bed> secBeds = bedsBySection.getOrDefault(sec.getId(), List.of());
				List<PublicBedDetail> vacantSecBeds = new ArrayList<>();
				for (Bed b : secBeds) {
					if (b.getStatus() == BedStatus.VACANT) {
						BigDecimal effectiveRent = b.getCustomRentOverride() != null ? b.getCustomRentOverride()
								: (sec.getRentPerBed() != null && sec.getRentPerBed().compareTo(BigDecimal.ZERO) > 0 ? sec.getRentPerBed() : r.getRent());
						PublicBedDetail bedDetail = new PublicBedDetail(b.getId(), b.getBedNumber(), b.getBedType(), effectiveRent, b.getStatus());
						vacantSecBeds.add(bedDetail);
						allRoomVacantBeds.add(bedDetail);
						totalVacant++;

						if (minRent == null || (effectiveRent != null && effectiveRent.compareTo(BigDecimal.ZERO) > 0 && effectiveRent.compareTo(minRent) < 0)) {
							minRent = effectiveRent;
						}
					}
				}
				sectionResponses.add(new PublicSectionDetail(
						sec.getId(),
						sec.getSectionName(),
						sec.getSharingType(),
						sec.getCapacity(),
						sec.getRentPerBed(),
						sec.getDepositPerBed(),
						sec.getAcType(),
						sec.isAttachedBathroom(),
						vacantSecBeds
				));
			}

			roomDetails.add(new PublicRoomDetail(
					r.getId(),
					r.getNumber(),
					r.getRoomName(),
					r.getBuildingName(),
					r.getFloorName(),
					r.getRoomType(),
					r.getAcType(),
					r.isAttachedBathroom(),
					r.isBalcony(),
					r.getGenderPolicy(),
					r.getCapacity(),
					r.getRent(),
					r.getDailyRent(),
					allRoomVacantBeds.size(),
					sharingResponses,
					sectionResponses,
					allRoomVacantBeds
			));
		}

		return new PublicPgDetailResponse(
				pg.getId(),
				pg.getName(),
				pg.getOwnerName(),
				pg.getMobile(),
				pg.getEmail(),
				pg.getAddress(),
				pg.getCity(),
				pg.getState(),
				pg.getTotalFloors(),
				pg.getStayType() != null ? pg.getStayType() : "PG",
				pg.getUnitLabel() != null ? pg.getUnitLabel() : "Room",
				pg.getLocationUrl(),
				pg.getGenderType() != null ? pg.getGenderType() : "CO_LIVING",
				pg.getUpiId(),
				pg.getUpiName(),
				pg.getDescription(),
				parseAmenities(pg.getAmenities()),
				minRent != null ? minRent : BigDecimal.valueOf(5000),
				roomList.size(),
				totalBeds,
				totalVacant,
				roomDetails
		);
	}

	@PostMapping("/inquiry")
	public BookingInquiryResponse submitInquiry(@Valid @RequestBody BookingInquiryRequest req) {
		Pg pg = pgs.findById(req.pgId()).orElseThrow(() -> new NotFoundException("PG not found"));
		LocalDate joinDate = null;
		if (req.expectedJoinDate() != null && !req.expectedJoinDate().isBlank()) {
			try {
				joinDate = LocalDate.parse(req.expectedJoinDate().trim());
			} catch (Exception ignored) {}
		}
		bookingRequestService.createInquiry(
				req.pgId(),
				req.roomId(),
				req.sectionId(),
				req.bedId(),
				req.name(),
				req.mobile(),
				req.email(),
				joinDate,
				req.message()
		);
		return new BookingInquiryResponse(true, "Your booking request for " + pg.getName() + " has been received! The PG manager will review and contact you on " + req.mobile() + ".");
	}

	private List<String> parseAmenities(String amenitiesStr) {
		if (amenitiesStr == null || amenitiesStr.isBlank()) {
			return DEFAULT_PG_AMENITIES;
		}
		List<String> list = new ArrayList<>();
		for (String item : amenitiesStr.split(",")) {
			String trimmed = item.trim();
			if (!trimmed.isEmpty()) {
				list.add(trimmed);
			}
		}
		return list.isEmpty() ? DEFAULT_PG_AMENITIES : list;
	}
}
