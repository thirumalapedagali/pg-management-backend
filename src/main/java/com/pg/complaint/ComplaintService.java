package com.pg.complaint;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.lookup.LookupService;
import com.pg.lookup.LookupService.LookupItem;
import com.pg.notification.WhatsAppService;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.push.PushService;
import com.pg.resident.Resident;
import com.pg.resident.ResidentRepository;
import com.pg.room.Bed;
import com.pg.room.BedRepository;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.tenant.TenantContext;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;

/**
 * Complaints raised by residents. The owner or manager of the property sees them, works on them and resolves them;
 * the resident who raised one is told about every change.
 */
@Slf4j
@Service
public class ComplaintService {

	public record ComplaintRequest(@NotBlank @Size(max = 40) String category, @NotBlank @Size(max = 120) String title,
			@NotBlank @Size(max = 1000) String description, ComplaintPriority priority) {
	}

	public record ComplaintUpdate(@NotNull ComplaintStatus status, @Size(max = 1000) String resolutionNotes) {
	}

	public record ComplaintResponse(Long id, String category, String categoryLabel, String title, String description,
			ComplaintPriority priority, ComplaintStatus status, LocalDateTime createdAt, LocalDateTime resolvedAt,
			String resolutionNotes, String handledBy, boolean residentSeen, Long residentId, String residentName,
			String residentPhone, String roomNumber, String bedNumber) {
	}

	private final ComplaintRepository complaints;
	private final ResidentRepository residents;
	private final RoomRepository rooms;
	private final BedRepository beds;
	private final PgRepository pgs;
	private final LookupService lookups;
	private final WhatsAppService whatsApp;
	private final PushService push;
	private final AppClock clock;

	public ComplaintService(ComplaintRepository complaints, ResidentRepository residents, RoomRepository rooms,
			BedRepository beds, PgRepository pgs, LookupService lookups, WhatsAppService whatsApp, PushService push,
			AppClock clock) {
		this.push = push;
		this.complaints = complaints;
		this.residents = residents;
		this.rooms = rooms;
		this.beds = beds;
		this.pgs = pgs;
		this.lookups = lookups;
		this.whatsApp = whatsApp;
		this.clock = clock;
	}

	/** Every complaint of the current property, newest first (owner / manager view). */
	@Transactional(readOnly = true)
	public List<ComplaintResponse> listAll() {
		Long pgId = TenantContext.requirePgId();
		return toResponses(pgId, complaints.findTop200ByPgIdOrderByIdDesc(pgId));
	}

	@Transactional(readOnly = true)
	public List<ComplaintResponse> listFor(Long residentId) {
		Long pgId = TenantContext.requirePgId();
		return toResponses(pgId, complaints.findByPgIdAndResidentIdOrderByIdDesc(pgId, residentId));
	}

	/** Complaints still waiting for someone to pick them up. */
	@Transactional(readOnly = true)
	public long countOpen() {
		return complaints.countByPgIdAndStatus(TenantContext.requirePgId(), ComplaintStatus.OPEN);
	}

	@Transactional
	public ComplaintResponse raise(Long residentId, ComplaintRequest req) {
		Long pgId = TenantContext.requirePgId();
		Resident resident = residents.findByPgIdAndId(pgId, residentId)
				.orElseThrow(() -> new BadRequestException("Resident not found"));
		String categoryCode = req.category().trim().toUpperCase();
		String categoryLabel = lookups.activeLabel(LookupService.COMPLAINT_CATEGORY, categoryCode)
				.orElseThrow(() -> new BadRequestException("Unknown complaint category"));

		Complaint c = new Complaint();
		c.setResidentId(residentId);
		c.setCategory(categoryCode);
		c.setTitle(req.title().trim());
		c.setDescription(req.description().trim());
		if (req.priority() != null) {
			c.setPriority(req.priority());
		}
		Complaint saved = complaints.save(c);

		notifyOwner(pgId, resident, saved, categoryLabel);
		try {
			push.toStaff(pgId, "New Complaint", "A new " + categoryLabel + " complaint has been raised by " + push.formatResidentDetails(pgId, resident) + ": \"" + saved.getTitle() + "\".",
					java.util.Map.of("type", "COMPLAINT"));
		} catch (Exception e) {
			log.warn("Failed to send complaint push notification to staff: {}", e.getMessage());
		}
		return toResponses(pgId, List.of(saved)).get(0);
	}

	/** Owner / manager moves a complaint along and, usually, records what was done. */
	@Transactional
	public ComplaintResponse update(Long id, ComplaintUpdate req, String staffUsername) {
		Long pgId = TenantContext.requirePgId();
		Complaint c = complaints.findByPgIdAndId(pgId, id).orElseThrow(() -> new NotFoundException("Complaint not found"));
		String notes = req.resolutionNotes() == null ? null : req.resolutionNotes().trim();
		if (notes != null && notes.isEmpty()) {
			notes = null;
		}
		boolean changed = c.getStatus() != req.status() || !java.util.Objects.equals(c.getResolutionNotes(), notes);
		if (!changed) {
			return toResponses(pgId, List.of(c)).get(0);
		}

		c.setStatus(req.status());
		c.setResolutionNotes(notes);
		c.setResolvedAt(req.status() == ComplaintStatus.RESOLVED ? clock.now() : null);
		c.setHandledBy(staffUsername);
		// The resident has not seen this change yet; the app shows an indicator until they open the list.
		c.setResidentSeen(false);
		Complaint saved = complaints.save(c);

		notifyResident(pgId, saved);
		push.toResident(pgId, saved.getResidentId(), "Complaint "+ saved.getStatus().label(),
				"Your complaint \""+ saved.getTitle() + "\" is now "+ saved.getStatus().label() + ".",
				java.util.Map.of("type", "COMPLAINT"));
		return toResponses(pgId, List.of(saved)).get(0);
	}

	/** The resident has opened the list, so the "something changed" indicator can go. */
	@Transactional
	public void markSeen(Long residentId) {
		Long pgId = TenantContext.requirePgId();
		List<Complaint> unseen = complaints.findByPgIdAndResidentIdAndResidentSeenFalse(pgId, residentId);
		unseen.forEach(c -> c.setResidentSeen(true));
		complaints.saveAll(unseen);
	}

	private void notifyOwner(Long pgId, Resident resident, Complaint c, String categoryLabel) {
		try {
			Pg pg = pgs.findById(pgId).orElse(null);
			if (pg == null || pg.getMobile() == null || pg.getMobile().isBlank()) {
				return;
			}
			Room room = rooms.findByPgIdAndId(pgId, resident.getRoomId()).orElse(null);
			String msg = String.format(
					"🛠️ *New Complaint - %s*\n\n"
					+ "Resident: *%s*\n"
					+ "Room: *%s*\n"
					+ "Category: %s\n"
					+ "Priority: %s\n\n"
					+ "*%s*\n%s\n\n"
					+ "Open the app to review and resolve it.",
					pg.getName(), resident.getName(), room != null ? room.getNumber() : "-", categoryLabel,
					c.getPriority(), c.getTitle(), c.getDescription());
			whatsApp.send(pgId, pg.getMobile(), msg);
		} catch (Exception ex) {
			log.warn("Could not notify the owner about complaint {}: {}", c.getId(), ex.getMessage());
		}
	}

	private void notifyResident(Long pgId, Complaint c) {
		try {
			Resident resident = residents.findByPgIdAndId(pgId, c.getResidentId()).orElse(null);
			if (resident == null || resident.getPhone() == null || resident.getPhone().isBlank()) {
				return;
			}
			Pg pg = pgs.findById(pgId).orElse(null);
			StringBuilder msg = new StringBuilder(String.format(
					"%s *Complaint %s - %s*\n\nHi %s,\nYour complaint \"%s\" is now *%s*.",
					c.getStatus() == ComplaintStatus.RESOLVED ? "✅" : "🔧", c.getStatus().label(),
					pg != null ? pg.getName() : "PG Management", resident.getName(), c.getTitle(),
					c.getStatus().label()));
			if (c.getResolutionNotes() != null) {
				msg.append("\n\n📝 ").append(c.getResolutionNotes());
			}
			whatsApp.send(pgId, resident.getPhone(), msg.toString());
		} catch (Exception ex) {
			log.warn("Could not notify the resident about complaint {}: {}", c.getId(), ex.getMessage());
		}
	}

	private List<ComplaintResponse> toResponses(Long pgId, List<Complaint> list) {
		if (list.isEmpty()) {
			return List.of();
		}
		Map<Long, Resident> residentById = residents.findByPgIdOrderByName(pgId).stream()
				.collect(Collectors.toMap(Resident::getId, Function.identity()));
		Map<Long, Room> roomById = rooms.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, Function.identity()));
		Map<Long, Bed> bedById = beds.findByPgIdOrderByBedNumber(pgId).stream()
				.collect(Collectors.toMap(Bed::getId, Function.identity()));
		Map<String, String> labelByCode = new HashMap<>();
		for (LookupItem item : lookups.list(LookupService.COMPLAINT_CATEGORY)) {
			labelByCode.put(item.code(), item.label());
		}
		return list.stream().map(c -> {
			Resident r = residentById.get(c.getResidentId());
			Room room = r != null ? roomById.get(r.getRoomId()) : null;
			Bed bed = r != null && r.getBedId() != null ? bedById.get(r.getBedId()) : null;
			return new ComplaintResponse(c.getId(), c.getCategory(),
					labelByCode.getOrDefault(c.getCategory(), c.getCategory()), c.getTitle(), c.getDescription(),
					c.getPriority(), c.getStatus(), c.getCreatedAt(), c.getResolvedAt(), c.getResolutionNotes(),
					c.getHandledBy(), c.isResidentSeen(), c.getResidentId(), r != null ? r.getName() : null,
					r != null ? r.getPhone() : null, room != null ? room.getNumber() : null,
					bed != null ? bed.getBedNumber() : null);
		}).toList();
	}
}
