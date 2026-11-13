package com.pg.publicapi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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

import com.pg.common.BadRequestException;
import com.pg.dashboard.DashboardCache;
import com.pg.dashboard.DashboardCache.PublicKey;
import com.pg.common.NotFoundException;
import com.pg.common.Paged;
import com.pg.pg.Pg;
import com.pg.pg.PgLocation;
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
			int vacantBeds,
			/** Occupied beds whose resident has an approved notice, and the earliest date one of them frees up. */
			int upcomingBeds,
			LocalDate availableFrom,
			List<String> images,
			/** Only when the request sent a position and the property has one saved. */
			Double distanceKm,
			Double latitude,
			Double longitude
	) {
		PublicPgSummaryResponse withDistance(Double km) {
			return new PublicPgSummaryResponse(id, name, ownerName, mobile, email, address, city, state, totalFloors,
					stayType, unitLabel, locationUrl, genderType, upiId, upiName, description, amenities,
					startingPrice, totalRooms, totalBeds, vacantBeds, upcomingBeds, availableFrom, images, km,
					latitude, longitude);
		}
	}

	public record PublicBedDetail(
			Long id,
			String bedNumber,
			BedType bedType,
			BigDecimal rent,
			BedStatus status,
			LocalDate availableFrom
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
			List<PublicBedDetail> allVacantBeds,
			List<String> images
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
			List<PublicRoomDetail> rooms,
			List<String> images
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
	private final com.pg.attachment.MediaFileRepository media;
	private final DashboardCache cache;

	public PublicPgController(PgRepository pgs, RoomRepository rooms, RoomSectionRepository sections,
			BedRepository beds, RoomSharingOptionRepository sharingOptions, ResidentRepository residents,
			com.pg.booking.BookingRequestService bookingRequestService, com.pg.attachment.MediaFileRepository media,
			DashboardCache cache) {
		this.media = media;
		this.cache = cache;
		this.pgs = pgs;
		this.rooms = rooms;
		this.sections = sections;
		this.beds = beds;
		this.sharingOptions = sharingOptions;
		this.residents = residents;
		this.bookingRequestService = bookingRequestService;
	}

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 50;

	/**
	 * Active properties, one page at a time (20 by default, at most 50). Send {@code latitude} and {@code longitude}
	 * to get the nearest first, each with its {@code distanceKm}; properties without a saved position come last.
	 * Without a position the oldest properties come first. Only the properties on the requested page are loaded
	 * in full.
	 */
	@GetMapping("/pgs")
	public Paged<PublicPgSummaryResponse> listPgs(@RequestParam(required = false) String city,
			@RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		PgLocation.validate(latitude, longitude);
		int pageNo = page == null ? 0 : page;
		int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
		if (pageNo < 0) {
			throw new BadRequestException("page cannot be negative");
		}
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}

		// The active properties are the same for every visitor, so they are read once and reused (see DashboardCache).
		String cityFilter = city == null ? "" : city.trim().toLowerCase();
		List<Pg> allPgs = cache.publicData(new PublicKey("active", cityFilter, null), () -> pgs.findAll().stream()
				.filter(Pg::isActive)
				.filter(p -> cityFilter.isEmpty() || p.getCity() != null && p.getCity().toLowerCase().contains(cityFilter))
				.toList());

		Map<Long, Double> distances = new HashMap<>();
		if (latitude != null) {
			for (Pg p : allPgs) {
				if (p.getLatitude() != null && p.getLongitude() != null) {
					distances.put(p.getId(), distanceKm(latitude, longitude, p.getLatitude(), p.getLongitude()));
				}
			}
			allPgs = allPgs.stream().sorted(Comparator
					.comparing((Pg p) -> distances.getOrDefault(p.getId(), Double.MAX_VALUE)).thenComparing(Pg::getId))
					.toList();
		} else {
			allPgs = allPgs.stream().sorted(Comparator.comparing(Pg::getId)).toList();
		}

		int total = allPgs.size();
		int from = (int) Math.min((long) pageNo * pageSize, total);
		List<Pg> pageItems = allPgs.subList(from, (int) Math.min((long) from + pageSize, total));
		int totalPages = (int) Math.ceil(total / (double) pageSize);
		if (pageItems.isEmpty()) {
			return new Paged<>(List.of(), pageNo, pageSize, total, totalPages);
		}

		// Cards are built once per property and reused; only the ones not cached yet are built now.
		Map<Long, PublicPgSummaryResponse> cards = new HashMap<>();
		List<Pg> missing = new ArrayList<>();
		for (Pg pg : pageItems) {
			PublicPgSummaryResponse hit = cache.publicDataIfPresent(new PublicKey("summary", null, pg.getId()));
			if (hit != null) {
				cards.put(pg.getId(), hit);
			} else {
				missing.add(pg);
			}
		}
		if (!missing.isEmpty()) {
			Map<Long, List<String>> pictures = new HashMap<>();
			media.findByOwnerTypeAndOwnerIdInOrderById("PROPERTY", missing.stream().map(Pg::getId).toList())
					.forEach(m -> pictures.computeIfAbsent(m.getOwnerId(), k -> new ArrayList<>()).add(m.getUrl()));
			for (Pg pg : missing) {
				PublicPgSummaryResponse card = summaryCard(pg, pictures.getOrDefault(pg.getId(), List.of()));
				cache.putPublicData(new PublicKey("summary", null, pg.getId()), card);
				cards.put(pg.getId(), card);
			}
		}

		List<PublicPgSummaryResponse> result = new ArrayList<>();
		for (Pg pg : pageItems) {
			Double km = distances.containsKey(pg.getId()) ? Math.round(distances.get(pg.getId()) * 100) / 100.0 : null;
			result.add(cards.get(pg.getId()).withDistance(km));
		}
		return new Paged<>(result, pageNo, pageSize, total, totalPages);
	}

	/** The list card of one property, without the distance (that depends on who is asking). */
	private PublicPgSummaryResponse summaryCard(Pg pg, List<String> pictures) {
		{
			List<Room> pgRooms = rooms.findByPgIdOrderByNumber(pg.getId());
			List<Bed> pgBeds = beds.findByPgIdOrderByBedNumber(pg.getId());
			int totalBeds = pgBeds.size();
			int vacantBeds = (int) pgBeds.stream().filter(b -> b.getStatus() == BedStatus.VACANT).count();
			Map<Long, BedStatus> bedStatus = new HashMap<>();
			pgBeds.forEach(b -> bedStatus.put(b.getId(), b.getStatus()));
			int upcomingBeds = 0;
			LocalDate soonest = null;
			for (com.pg.resident.Resident res : residents.findByPgIdAndStatusOrderByName(pg.getId(), com.pg.resident.ResidentStatus.ACTIVE)) {
				if (res.getBedId() != null && res.getNoticeStatus() == com.pg.resident.NoticeStatus.APPROVED
						&& res.getVacateOn() != null && bedStatus.get(res.getBedId()) != null
						&& bedStatus.get(res.getBedId()) != BedStatus.VACANT) {
					upcomingBeds++;
					if (soonest == null || res.getVacateOn().isBefore(soonest)) {
						soonest = res.getVacateOn();
					}
				}
			}

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
			return new PublicPgSummaryResponse(
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
					vacantBeds,
					upcomingBeds,
					soonest,
					pictures,
					null,
					pg.getLatitude(),
					pg.getLongitude()
			);
		}
	}

	/** Great-circle (haversine) distance in kilometres. */
	private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
				* Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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

		Map<Long, List<String>> roomPictures = new HashMap<>();
		media.findByPgIdAndOwnerTypeOrderById(id, "ROOM")
				.forEach(m -> roomPictures.computeIfAbsent(m.getOwnerId(), k -> new ArrayList<>()).add(m.getUrl()));
		List<String> propertyPictures = media.findByOwnerTypeAndOwnerIdInOrderById("PROPERTY", List.of(id)).stream()
				.map(com.pg.attachment.MediaFile::getUrl).toList();

		int totalBeds = bedList.size();
		int totalVacant = 0;
		// Occupied beds whose resident has an owner-approved notice: bed id -> date it frees up.
		Map<Long, LocalDate> freeingUp = new HashMap<>();
		for (com.pg.resident.Resident res : residents.findByPgIdAndStatusOrderByName(id, com.pg.resident.ResidentStatus.ACTIVE)) {
			if (res.getBedId() != null && res.getNoticeStatus() == com.pg.resident.NoticeStatus.APPROVED && res.getVacateOn() != null) {
				freeingUp.put(res.getBedId(), res.getVacateOn());
			}
		}
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
					LocalDate availableFrom = b.getStatus() == BedStatus.VACANT ? null : freeingUp.get(b.getId());
					if (b.getStatus() == BedStatus.VACANT || availableFrom != null) {
						BigDecimal effectiveRent = b.getCustomRentOverride() != null ? b.getCustomRentOverride()
								: (sec.getRentPerBed() != null && sec.getRentPerBed().compareTo(BigDecimal.ZERO) > 0 ? sec.getRentPerBed() : r.getRent());
						PublicBedDetail bedDetail = new PublicBedDetail(b.getId(), b.getBedNumber(), b.getBedType(), effectiveRent, b.getStatus(), availableFrom);
						vacantSecBeds.add(bedDetail);
						allRoomVacantBeds.add(bedDetail);
						if (availableFrom == null) {
							totalVacant++;
						}

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
					(int) allRoomVacantBeds.stream().filter(x -> x.availableFrom() == null).count(),
					sharingResponses,
					sectionResponses,
					allRoomVacantBeds,
					roomPictures.getOrDefault(r.getId(), List.of())
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
				roomDetails,
				propertyPictures
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
