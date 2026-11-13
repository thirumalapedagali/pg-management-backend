package com.pg.room;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.billing.Billing;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.dashboard.DashboardCache;
import com.pg.resident.Resident;
import com.pg.resident.ResidentRentChange;
import com.pg.resident.ResidentRentChangeRepository;
import com.pg.resident.ResidentRepository;
import com.pg.resident.ResidentStatus;
import com.pg.resident.StayType;
import com.pg.tenant.TenantContext;
import com.pg.user.Role;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class RoomService {

	public record BedRequest(String bedNumber, BedType bedType, BigDecimal customRentOverride) {
	}

	public record SectionRentRequest(Long sectionId, BigDecimal rentPerBed, BigDecimal depositPerBed) {
	}

	/**
	 * Either {@code rent} (room or flat with a single rate) or {@code sections} (rent per bed for each section).
	 * The deposit is optional and, when sent, is changed together with the rent.
	 */
	public record RentUpdateRequest(BigDecimal rent, BigDecimal deposit, List<SectionRentRequest> sections) {
	}

	public record SectionRequest(String sectionName, SharingType sharingType, Integer capacity,
			BigDecimal rentPerBed, BigDecimal depositPerBed, AcType acType, Boolean attachedBathroom,
			List<BedRequest> beds) {
	}

	public record SharingOptionRequest(SharingType sharingType, Integer capacity,
			@NotNull @DecimalMin("0.00") BigDecimal rentPerBed, BigDecimal depositPerBed) {
	}

	public record SharingOptionResponse(Long id, SharingType sharingType, int capacity,
			BigDecimal rentPerBed, BigDecimal depositPerBed, boolean active) {
	}

	public record RoomRequest(@NotBlank @Size(max = 30) String number, String roomName, String buildingName,
			String floorName, RoomType roomType, AcType acType, Boolean attachedBathroom, Boolean balcony,
			GenderPolicy genderPolicy, @Min(1) Integer capacity, @DecimalMin("0.00") BigDecimal rent,
			BigDecimal dailyRent,
			List<SharingOptionRequest> sharingOptions, List<SectionRequest> sections) {
	}

	public record BedResponse(Long id, String bedNumber, BedType bedType, BigDecimal rent, BedStatus status,
			Long residentId, String residentName, java.time.LocalDate availableFrom) {
	}

	public record SectionResponse(Long id, String sectionName, SharingType sharingType, int capacity,
			BigDecimal rentPerBed, BigDecimal depositPerBed, AcType acType, boolean attachedBathroom,
			List<BedResponse> beds) {
	}

	/** occupants is null for residents (they only see availability). */
	public record RoomResponse(Long id, String number, String roomName, String buildingName, String floorName,
			RoomType roomType, AcType acType, boolean attachedBathroom, boolean balcony, GenderPolicy genderPolicy,
			int capacity, BigDecimal rent, BigDecimal dailyRent, int occupied, int vacant,
			List<SharingOptionResponse> sharingOptions, List<SectionResponse> sections,
			List<String> occupants, java.time.LocalDate availableFrom) {
	}

	private final RoomRepository rooms;
	private final RoomSectionRepository sections;
	private final BedRepository beds;
	private final RoomSharingOptionRepository sharingOptions;
	private final ResidentRepository residents;
	private final ResidentRentChangeRepository rentChanges;
	private final Billing billing;
	private final DashboardCache cache;
	private final com.pg.config.RoomAmenityRepository roomAmenities;
	private final com.pg.attachment.MediaService media;

	public RoomService(RoomRepository rooms, RoomSectionRepository sections, BedRepository beds,
			RoomSharingOptionRepository sharingOptions, ResidentRepository residents,
			ResidentRentChangeRepository rentChanges, Billing billing, DashboardCache cache,
			com.pg.config.RoomAmenityRepository roomAmenities, com.pg.attachment.MediaService media) {
		this.media = media;
		this.cache = cache;
		this.roomAmenities = roomAmenities;
		this.rentChanges = rentChanges;
		this.billing = billing;
		this.rooms = rooms;
		this.sections = sections;
		this.beds = beds;
		this.sharingOptions = sharingOptions;
		this.residents = residents;
	}

	/** Cached per property until a room or resident changes (see CacheEvictionFilter). */
	public List<RoomResponse> list() {
		Long pgId = TenantContext.requirePgId();
		boolean staff = !Role.RESIDENT.name().equals(TenantContext.getRole());
		return cache.rooms(pgId, staff, () -> load(pgId, staff));
	}

	private List<RoomResponse> load(Long pgId, boolean staff) {
		List<Room> roomList = rooms.findByPgIdOrderByNumber(pgId);
		List<RoomSection> sectionList = sections.findByPgIdOrderBySectionName(pgId);
		List<Bed> bedList = beds.findByPgIdOrderByBedNumber(pgId);
		List<RoomSharingOption> optionList = sharingOptions.findByPgIdOrderByCapacity(pgId);
		List<Resident> residentList = residents.findByPgIdAndStatusOrderByName(pgId, com.pg.resident.ResidentStatus.ACTIVE);

		Map<Long, Resident> residentById = residentList.stream()
				.collect(Collectors.toMap(Resident::getId, r -> r, (a, b) -> a));

		Map<Long, List<Resident>> residentsByRoom = new HashMap<>();
		for (Resident r : residentList) {
			residentsByRoom.computeIfAbsent(r.getRoomId(), k -> new ArrayList<>()).add(r);
		}

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

		return roomList.stream().map(r -> {
			List<Resident> roomResidents = residentsByRoom.getOrDefault(r.getId(), List.of());
			List<String> occupantNames = roomResidents.stream().map(Resident::getName).toList();
			List<RoomSection> roomSections = sectionsByRoom.getOrDefault(r.getId(), List.of());
			List<RoomSharingOption> roomOptions = optionsByRoom.getOrDefault(r.getId(), List.of());

			List<SharingOptionResponse> sharingResponses = roomOptions.stream().map(opt ->
					new SharingOptionResponse(opt.getId(), opt.getSharingType(), opt.getCapacity(),
							opt.getRentPerBed(), opt.getDepositPerBed(), opt.isActive())
			).toList();

			List<SectionResponse> sectionResponses = roomSections.stream().map(s -> {
				List<Bed> secBeds = bedsBySection.getOrDefault(s.getId(), List.of());
				List<BedResponse> bedResponses = secBeds.stream().map(b -> {
					Resident res = b.getResidentId() != null ? residentById.get(b.getResidentId()) : null;
					BigDecimal bedRent = b.getCustomRentOverride() != null ? b.getCustomRentOverride() : s.getRentPerBed();
					return new BedResponse(b.getId(), b.getBedNumber(), b.getBedType(), bedRent, b.getStatus(),
							staff && res != null ? res.getId() : null,
							staff && res != null ? res.getName() : null, availableFrom(res));
				}).toList();

				return new SectionResponse(s.getId(), s.getSectionName(), s.getSharingType(), s.getCapacity(),
						s.getRentPerBed(), s.getDepositPerBed(), s.getAcType() != null ? s.getAcType() : r.getAcType(),
						s.isAttachedBathroom(), bedResponses);
			}).toList();

			int totalCapacity = r.getCapacity();
			int occupied = roomResidents.size();
			int vacant = Math.max(0, totalCapacity - occupied);

			java.time.LocalDate earliestAvailableFrom = sectionResponses.stream()
					.flatMap(s -> s.beds().stream())
					.map(BedResponse::availableFrom)
					.filter(java.util.Objects::nonNull)
					.min(java.time.LocalDate::compareTo)
					.orElse(null);

			return new RoomResponse(r.getId(), r.getNumber(), r.getRoomName(), r.getBuildingName(), r.getFloorName(),
					r.getRoomType(), r.getAcType(), r.isAttachedBathroom(), r.isBalcony(), r.getGenderPolicy(),
					totalCapacity, r.getRent(), r.getDailyRent(), occupied, vacant, sharingResponses, sectionResponses, staff ? occupantNames : null, earliestAvailableFrom);
		}).toList();
	}

	@Transactional
	public RoomResponse create(RoomRequest req) {
		Long pgId = TenantContext.requirePgId();
		String number = req.number().trim();
		if (rooms.existsByPgIdAndNumber(pgId, number)) {
			throw new BadRequestException("Room " + number + " already exists");
		}

		Room room = new Room();
		applyRoomDetails(room, req, number);

		int computedCapacity = 0;
		BigDecimal computedRent = req.rent() != null ? req.rent() : BigDecimal.ZERO;

		// Calculate capacity and rent from sharing options if provided
		if (req.sharingOptions() != null && !req.sharingOptions().isEmpty()) {
			for (SharingOptionRequest opt : req.sharingOptions()) {
				int cap = opt.capacity() != null ? opt.capacity() : sharingTypeToCapacity(opt.sharingType());
				if (cap > computedCapacity) {
					computedCapacity = cap;
				}
				if (computedRent.equals(BigDecimal.ZERO) && opt.rentPerBed() != null) {
					computedRent = opt.rentPerBed();
				}
			}
		}

		if (req.sections() != null && !req.sections().isEmpty()) {
			int secTotalCap = 0;
			for (SectionRequest secReq : req.sections()) {
				int secCap = secReq.beds() != null && !secReq.beds().isEmpty() ? secReq.beds().size()
						: (secReq.capacity() != null ? secReq.capacity() : 1);
				secTotalCap += secCap;
				if (computedRent.equals(BigDecimal.ZERO) && secReq.rentPerBed() != null) {
					computedRent = secReq.rentPerBed();
				}
			}
			computedCapacity = Math.max(computedCapacity, secTotalCap);
		}

		if (computedCapacity <= 0) {
			computedCapacity = req.capacity() != null ? req.capacity() : 1;
		}
		if (computedRent.equals(BigDecimal.ZERO) && req.rent() != null) {
			computedRent = req.rent();
		}

		room.setCapacity(computedCapacity);
		room.setRent(computedRent);
		room = rooms.save(room);

		// Save sharing options
		if (req.sharingOptions() != null && !req.sharingOptions().isEmpty()) {
			for (SharingOptionRequest optReq : req.sharingOptions()) {
				RoomSharingOption opt = new RoomSharingOption();
				opt.setRoomId(room.getId());
				opt.setSharingType(optReq.sharingType() != null ? optReq.sharingType() : determineSharingType(optReq.capacity() != null ? optReq.capacity() : 1));
				opt.setCapacity(optReq.capacity() != null ? optReq.capacity() : sharingTypeToCapacity(opt.getSharingType()));
				opt.setRentPerBed(optReq.rentPerBed());
				opt.setDepositPerBed(optReq.depositPerBed() != null ? optReq.depositPerBed() : BigDecimal.ZERO);
				opt.setActive(true);
				sharingOptions.save(opt);
			}
		} else {
			// Auto default sharing option
			RoomSharingOption opt = new RoomSharingOption();
			opt.setRoomId(room.getId());
			opt.setSharingType(determineSharingType(room.getCapacity()));
			opt.setCapacity(room.getCapacity());
			opt.setRentPerBed(room.getRent());
			opt.setDepositPerBed(BigDecimal.ZERO);
			opt.setActive(true);
			sharingOptions.save(opt);
		}

		// Save sections and beds
		if (req.sections() != null && !req.sections().isEmpty()) {
			for (SectionRequest secReq : req.sections()) {
				RoomSection sec = new RoomSection();
				sec.setRoomId(room.getId());
				sec.setSectionName(secReq.sectionName() != null && !secReq.sectionName().isBlank()
						? secReq.sectionName().trim() : "Section");
				sec.setSharingType(secReq.sharingType() != null ? secReq.sharingType() : SharingType.PRIVATE);
				int cap = secReq.beds() != null && !secReq.beds().isEmpty() ? secReq.beds().size()
						: (secReq.capacity() != null ? secReq.capacity() : 1);
				sec.setCapacity(cap);
				sec.setRentPerBed(secReq.rentPerBed() != null ? secReq.rentPerBed() : room.getRent());
				sec.setDepositPerBed(secReq.depositPerBed() != null ? secReq.depositPerBed() : BigDecimal.ZERO);
				sec.setAcType(secReq.acType());
				sec.setAttachedBathroom(Boolean.TRUE.equals(secReq.attachedBathroom()));
				sec = sections.save(sec);

				if (secReq.beds() != null && !secReq.beds().isEmpty()) {
					for (BedRequest bReq : secReq.beds()) {
						Bed bed = new Bed();
						bed.setRoomId(room.getId());
						bed.setSectionId(sec.getId());
						bed.setBedNumber(bReq.bedNumber() != null ? bReq.bedNumber().trim() : "Bed");
						bed.setBedType(bReq.bedType() != null ? bReq.bedType() : BedType.SINGLE);
						bed.setCustomRentOverride(bReq.customRentOverride());
						bed.setStatus(BedStatus.VACANT);
						beds.save(bed);
					}
				} else {
					for (int i = 1; i <= cap; i++) {
						Bed bed = new Bed();
						bed.setRoomId(room.getId());
						bed.setSectionId(sec.getId());
						bed.setBedNumber("Bed " + i);
						bed.setBedType(BedType.SINGLE);
						bed.setStatus(BedStatus.VACANT);
						beds.save(bed);
					}
				}
			}
		} else {
			// Create single default section and beds
			RoomSection sec = new RoomSection();
			sec.setRoomId(room.getId());
			sec.setSectionName("Main Room");
			sec.setSharingType(determineSharingType(room.getCapacity()));
			sec.setCapacity(room.getCapacity());
			sec.setRentPerBed(room.getRent());
			sec.setDepositPerBed(BigDecimal.ZERO);
			sec.setAcType(room.getAcType());
			sec.setAttachedBathroom(room.isAttachedBathroom());
			sec = sections.save(sec);

			for (int i = 1; i <= room.getCapacity(); i++) {
				Bed bed = new Bed();
				bed.setRoomId(room.getId());
				bed.setSectionId(sec.getId());
				bed.setBedNumber("Bed " + i);
				bed.setBedType(BedType.SINGLE);
				bed.setStatus(BedStatus.VACANT);
				beds.save(bed);
			}
		}

		return getRoomResponse(room.getId());
	}

	@Transactional
	public RoomResponse update(Long id, RoomRequest req) {
		Long pgId = TenantContext.requirePgId();
		Room room = rooms.lockByPgIdAndId(pgId, id).orElseThrow(() -> new NotFoundException("Room not found"));
		String number = req.number().trim();
		if (rooms.existsByPgIdAndNumberAndIdNot(pgId, number, id)) {
			throw new BadRequestException("Room " + number + " already exists");
		}

		long occupied = residents.countByPgIdAndRoomIdAndStatus(pgId, id, com.pg.resident.ResidentStatus.ACTIVE);
		int newCapacity = req.capacity() != null ? req.capacity() : room.getCapacity();
		if (req.sharingOptions() != null && !req.sharingOptions().isEmpty()) {
			for (SharingOptionRequest opt : req.sharingOptions()) {
				int cap = opt.capacity() != null ? opt.capacity() : sharingTypeToCapacity(opt.sharingType());
				if (cap > newCapacity) {
					newCapacity = cap;
				}
			}
		}

		if (newCapacity < occupied) {
			throw new BadRequestException("Capacity cannot be below the current " + occupied + " residents");
		}

		applyRoomDetails(room, req, number);
		room.setCapacity(newCapacity);
		if (req.rent() != null) {
			room.setRent(req.rent());
		}
		rooms.save(room);

		// Update sharing options if supplied
		if (req.sharingOptions() != null && !req.sharingOptions().isEmpty()) {
			sharingOptions.deleteByPgIdAndRoomId(pgId, id);
			for (SharingOptionRequest optReq : req.sharingOptions()) {
				RoomSharingOption opt = new RoomSharingOption();
				opt.setRoomId(room.getId());
				opt.setSharingType(optReq.sharingType() != null ? optReq.sharingType() : determineSharingType(optReq.capacity() != null ? optReq.capacity() : 1));
				opt.setCapacity(optReq.capacity() != null ? optReq.capacity() : sharingTypeToCapacity(opt.getSharingType()));
				opt.setRentPerBed(optReq.rentPerBed());
				opt.setDepositPerBed(optReq.depositPerBed() != null ? optReq.depositPerBed() : BigDecimal.ZERO);
				opt.setActive(true);
				sharingOptions.save(opt);
			}
		}

		// Ensure beds match if simple structure
		List<RoomSection> secList = sections.findByPgIdAndRoomIdOrderBySectionName(pgId, id);
		if (secList.size() == 1) {
			RoomSection sec = secList.get(0);
			sec.setCapacity(newCapacity);
			if (req.rent() != null) {
				sec.setRentPerBed(req.rent());
			}
			sec.setSharingType(determineSharingType(newCapacity));
			sections.save(sec);

			List<Bed> existingBeds = beds.findByPgIdAndSectionIdOrderByBedNumber(pgId, sec.getId());
			if (existingBeds.size() < newCapacity) {
				for (int i = existingBeds.size() + 1; i <= newCapacity; i++) {
					Bed b = new Bed();
					b.setRoomId(room.getId());
					b.setSectionId(sec.getId());
					b.setBedNumber("Bed " + i);
					b.setBedType(BedType.SINGLE);
					b.setStatus(BedStatus.VACANT);
					beds.save(b);
				}
			}
		}

		return getRoomResponse(room.getId());
	}

	/**
	 * Changes the rent of a room (or of its sections). New residents get the new rent straight away. Current
	 * monthly residents who pay the old standard rate move to the new rent from the start of their next billing
	 * cycle, so cycles already billed are untouched. Residents on a custom rent are left alone.
	 */
	@Transactional
	public RoomResponse updateRent(Long id, RentUpdateRequest req) {
		Long pgId = TenantContext.requirePgId();
		Room room = rooms.lockByPgIdAndId(pgId, id).orElseThrow(() -> new NotFoundException("Room not found"));
		List<RoomSection> secList = sections.findByPgIdAndRoomIdOrderBySectionName(pgId, id);
		List<Resident> active = residents.findByPgIdAndRoomIdAndStatusOrderByName(pgId, id, ResidentStatus.ACTIVE);
		BigDecimal oldRoomRent = room.getRent();

		boolean bySection = req.sections() != null && !req.sections().isEmpty();
		if (!bySection && req.rent() == null) {
			throw new BadRequestException("Enter the new rent");
		}
		if (!bySection && secList.size() > 1) {
			throw new BadRequestException("This room has several sections: set the rent of each section");
		}

		// A resident without a rent of their own would silently follow the room's rent: pin it before changing it.
		for (Resident r : active) {
			if (r.getRentOverride() == null) {
				r.setRentOverride(oldRoomRent);
				residents.save(r);
			}
		}

		if (bySection) {
			Map<Long, RoomSection> byId = secList.stream().collect(Collectors.toMap(RoomSection::getId, s -> s));
			for (SectionRentRequest sr : req.sections()) {
				RoomSection sec = sr.sectionId() == null ? null : byId.get(sr.sectionId());
				if (sec == null) {
					throw new BadRequestException("Section not found in this room");
				}
				if (sr.depositPerBed() != null) {
					BigDecimal newDeposit = requireDeposit(sr.depositPerBed());
					sec.setDepositPerBed(newDeposit);
					sections.save(sec);
					setOptionDeposit(pgId, id,
							o -> o.getSharingType() == sec.getSharingType() && o.getCapacity() == sec.getCapacity(),
							newDeposit);
				}
				BigDecimal newRent = requireRent(sr.rentPerBed());
				BigDecimal oldRent = sec.getRentPerBed();
				if (oldRent.compareTo(newRent) == 0) {
					continue;
				}
				sec.setRentPerBed(newRent);
				sections.save(sec);
				setOptionRent(pgId, id, o -> o.getSharingType() == sec.getSharingType() && o.getCapacity() == sec.getCapacity(),
						newRent);
				scheduleForResidents(active.stream().filter(r -> sec.getId().equals(r.getSectionId())).toList(),
						oldRent, newRent);
			}
			int totalBeds = secList.stream().mapToInt(RoomSection::getCapacity).sum();
			if (totalBeds > 0) {
				BigDecimal total = secList.stream()
						.map(s -> s.getRentPerBed().multiply(BigDecimal.valueOf(s.getCapacity())))
						.reduce(BigDecimal.ZERO, BigDecimal::add);
				room.setRent(total.divide(BigDecimal.valueOf(totalBeds), 2, RoundingMode.HALF_UP));
			}
		} else {
			BigDecimal newRent = requireRent(req.rent());
			RoomSection sec = secList.isEmpty() ? null : secList.get(0);
			if (req.deposit() != null) {
				BigDecimal newDeposit = requireDeposit(req.deposit());
				if (sec != null) {
					sec.setDepositPerBed(newDeposit);
					sections.save(sec);
				}
				setOptionDeposit(pgId, id, o -> true, newDeposit);
			}
			BigDecimal oldRent = sec != null ? sec.getRentPerBed() : oldRoomRent;
			if (oldRent.compareTo(newRent) != 0 || oldRoomRent.compareTo(newRent) != 0) {
				room.setRent(newRent);
				if (sec != null) {
					sec.setRentPerBed(newRent);
					sections.save(sec);
				}
				setOptionRent(pgId, id, o -> true, newRent);
				scheduleForResidents(active, oldRent, newRent);
			}
		}
		rooms.save(room);
		return getRoomResponse(id);
	}

	private static BigDecimal requireRent(BigDecimal rent) {
		if (rent == null || rent.signum() < 0) {
			throw new BadRequestException("Rent must be zero or more");
		}
		return rent;
	}

	private static BigDecimal requireDeposit(BigDecimal deposit) {
		if (deposit == null || deposit.signum() < 0) {
			throw new BadRequestException("Deposit must be zero or more");
		}
		return deposit;
	}

	private void setOptionDeposit(Long pgId, Long roomId, Predicate<RoomSharingOption> match, BigDecimal deposit) {
		for (RoomSharingOption opt : sharingOptions.findByPgIdAndRoomIdOrderByCapacity(pgId, roomId)) {
			if (match.test(opt)) {
				opt.setDepositPerBed(deposit);
				sharingOptions.save(opt);
			}
		}
	}


	private void setOptionRent(Long pgId, Long roomId, Predicate<RoomSharingOption> match, BigDecimal rent) {
		for (RoomSharingOption opt : sharingOptions.findByPgIdAndRoomIdOrderByCapacity(pgId, roomId)) {
			if (match.test(opt)) {
				opt.setRentPerBed(rent);
				sharingOptions.save(opt);
			}
		}
	}

	/** Schedules {@code newRent} from each resident's next billing cycle, if they are paying {@code oldRent}. */
	private void scheduleForResidents(List<Resident> affected, BigDecimal oldRent, BigDecimal newRent) {
		for (Resident r : affected) {
			if (r.getStayType() != StayType.MONTHLY || r.getJoinedOn() == null) {
				continue;
			}
			LocalDate start = billing.nextCycleStart(r);
			List<ResidentRentChange> existing = billing.changesFor(r);
			if (Billing.rentForCycle(r.getRentOverride(), existing, start).compareTo(oldRent) != 0) {
				continue; // negotiated / custom rent: not tied to the room's rate
			}
			List<ResidentRentChange> keep = new ArrayList<>();
			for (ResidentRentChange c : existing) {
				if (c.getEffectiveFrom().isBefore(start)) {
					keep.add(c);
				} else {
					rentChanges.delete(c);
				}
			}
			if (Billing.rentForCycle(r.getRentOverride(), keep, start).compareTo(newRent) != 0) {
				ResidentRentChange change = new ResidentRentChange();
				change.setResidentId(r.getId());
				change.setEffectiveFrom(start);
				change.setRent(newRent);
				rentChanges.save(change);
			}
		}
	}

	@Transactional
	public void delete(Long id) {
		Long pgId = TenantContext.requirePgId();
		Room room = rooms.lockByPgIdAndId(pgId, id).orElseThrow(() -> new NotFoundException("Room not found"));
		if (residents.countByPgIdAndRoomIdAndStatus(pgId, id, com.pg.resident.ResidentStatus.ACTIVE) > 0) {
			throw new BadRequestException("Move or remove the residents in this room first");
		}
		beds.deleteByPgIdAndRoomId(pgId, id);
		sections.deleteByPgIdAndRoomId(pgId, id);
		sharingOptions.deleteByPgIdAndRoomId(pgId, id);
		roomAmenities.deleteByPgIdAndRoomId(pgId, id);
		rooms.delete(room);
		media.removeAll("ROOM", pgId, id);
	}

	private void applyRoomDetails(Room room, RoomRequest req, String number) {
		room.setNumber(number);
		if (req.roomName() != null) room.setRoomName(req.roomName().trim());
		if (req.buildingName() != null) room.setBuildingName(req.buildingName().trim());
		if (req.floorName() != null) room.setFloorName(req.floorName().trim());
		if (req.roomType() != null) room.setRoomType(req.roomType());
		if (req.acType() != null) room.setAcType(req.acType());
		if (req.attachedBathroom() != null) room.setAttachedBathroom(req.attachedBathroom());
		if (req.balcony() != null) room.setBalcony(req.balcony());
		if (req.genderPolicy() != null) room.setGenderPolicy(req.genderPolicy());
		if (req.dailyRent() != null) room.setDailyRent(req.dailyRent());
	}

	private SharingType determineSharingType(int capacity) {
		return switch (capacity) {
			case 1 -> SharingType.PRIVATE;
			case 2 -> SharingType.TWO_SHARING;
			case 3 -> SharingType.THREE_SHARING;
			case 4 -> SharingType.FOUR_SHARING;
			default -> SharingType.DORM;
		};
	}

	private int sharingTypeToCapacity(SharingType sharingType) {
		if (sharingType == null) return 1;
		return switch (sharingType) {
			case PRIVATE -> 1;
			case TWO_SHARING -> 2;
			case THREE_SHARING -> 3;
			case FOUR_SHARING -> 4;
			case WHOLE_ROOM -> 1;
			case DORM -> 6;
		};
	}

	/** Date an occupied bed frees up, once the owner has approved the occupant's notice. */
	private static java.time.LocalDate availableFrom(Resident res) {
		return res != null && res.getNoticeStatus() == com.pg.resident.NoticeStatus.APPROVED ? res.getVacateOn() : null;
	}

	private RoomResponse getRoomResponse(Long roomId) {
		Long pgId = TenantContext.requirePgId();
		Room r = rooms.findByPgIdAndId(pgId, roomId).orElseThrow();
		List<Resident> roomResidents = residents.findByPgIdAndRoomIdOrderByName(pgId, roomId);
		List<String> occupantNames = roomResidents.stream().map(Resident::getName).toList();
		List<RoomSection> roomSections = sections.findByPgIdAndRoomIdOrderBySectionName(pgId, roomId);
		List<Bed> roomBeds = beds.findByPgIdAndRoomIdOrderByBedNumber(pgId, roomId);
		List<RoomSharingOption> roomOptions = sharingOptions.findByPgIdAndRoomIdOrderByCapacity(pgId, roomId);

		Map<Long, Resident> residentById = roomResidents.stream()
				.collect(Collectors.toMap(Resident::getId, res -> res, (a, b) -> a));

		Map<Long, List<Bed>> bedsBySection = new HashMap<>();
		for (Bed b : roomBeds) {
			bedsBySection.computeIfAbsent(b.getSectionId(), k -> new ArrayList<>()).add(b);
		}

		List<SharingOptionResponse> sharingResponses = roomOptions.stream().map(opt ->
				new SharingOptionResponse(opt.getId(), opt.getSharingType(), opt.getCapacity(),
						opt.getRentPerBed(), opt.getDepositPerBed(), opt.isActive())
		).toList();

		List<SectionResponse> secResponses = roomSections.stream().map(s -> {
			List<Bed> secBeds = bedsBySection.getOrDefault(s.getId(), List.of());
			List<BedResponse> bedResponses = secBeds.stream().map(b -> {
				Resident res = b.getResidentId() != null ? residentById.get(b.getResidentId()) : null;
				BigDecimal bedRent = b.getCustomRentOverride() != null ? b.getCustomRentOverride() : s.getRentPerBed();
				return new BedResponse(b.getId(), b.getBedNumber(), b.getBedType(), bedRent, b.getStatus(),
						res != null ? res.getId() : null, res != null ? res.getName() : null, availableFrom(res));
			}).toList();

			return new SectionResponse(s.getId(), s.getSectionName(), s.getSharingType(), s.getCapacity(),
					s.getRentPerBed(), s.getDepositPerBed(), s.getAcType() != null ? s.getAcType() : r.getAcType(),
					s.isAttachedBathroom(), bedResponses);
		}).toList();

		int occupied = roomResidents.size();
		int vacant = Math.max(0, r.getCapacity() - occupied);

		java.time.LocalDate earliestAvailableFrom = secResponses.stream()
				.flatMap(s -> s.beds().stream())
				.map(BedResponse::availableFrom)
				.filter(java.util.Objects::nonNull)
				.min(java.time.LocalDate::compareTo)
				.orElse(null);

		return new RoomResponse(r.getId(), r.getNumber(), r.getRoomName(), r.getBuildingName(), r.getFloorName(),
				r.getRoomType(), r.getAcType(), r.isAttachedBathroom(), r.isBalcony(), r.getGenderPolicy(),
				r.getCapacity(), r.getRent(), r.getDailyRent(), occupied, vacant, sharingResponses, secResponses, occupantNames, earliestAvailableFrom);
	}
}
