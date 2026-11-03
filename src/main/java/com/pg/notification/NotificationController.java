package com.pg.notification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.billing.Billing;
import com.pg.common.BadRequestException;
import com.pg.notification.usage.WhatsappMessageLog;
import com.pg.notification.usage.WhatsappMessageLogRepository;
import com.pg.payment.PaymentRepository;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.resident.Resident;
import com.pg.resident.ResidentRepository;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.tenant.TenantContext;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

	public record SendCustomMessageRequest(String to, String message) {
	}

	public record ReminderSummaryResponse(int sentCount, int skippedCount) {
	}

	private final WhatsAppService whatsAppService;
	private final ResidentRepository residentRepository;
	private final RoomRepository roomRepository;
	private final PaymentRepository paymentRepository;
	private final PgRepository pgRepository;
	private final WhatsappMessageLogRepository logRepository;
	private final Billing billing;

	@PostMapping("/remind-due/{residentId}")
	public void remindDue(@PathVariable Long residentId) {
		Long pgId = TenantContext.requirePgId();
		Resident resident = residentRepository.findByPgIdAndId(pgId, residentId)
				.orElseThrow(() -> new BadRequestException("Resident not found"));

		Room room = roomRepository.findByPgIdAndId(pgId, resident.getRoomId()).orElse(null);
		BigDecimal billed = billing.billed(resident, room);
		BigDecimal paid = paymentRepository.sumForResident(pgId, residentId);
		BigDecimal due = Billing.due(billed, paid);

		if (due.signum() <= 0) {
			throw new BadRequestException("Resident has no outstanding dues");
		}

		Pg pg = pgRepository.findById(pgId).orElse(null);
		String pgName = pg != null ? pg.getName() : "PG Management";
		String pgPhone = pg != null ? pg.getMobile() : null;

		whatsAppService.sendDueReminder(pgId, pgName, resident.getName(), resident.getPhone(),
				room != null ? room.getNumber() : "-", due, pgPhone);
	}

	@PostMapping("/remind-all-dues")
	public ReminderSummaryResponse remindAllDues() {
		Long pgId = TenantContext.requirePgId();
		Pg pg = pgRepository.findById(pgId).orElse(null);
		String pgName = pg != null ? pg.getName() : "PG Management";
		String pgPhone = pg != null ? pg.getMobile() : null;

		Map<Long, Room> roomById = roomRepository.findByPgIdOrderByNumber(pgId).stream()
				.collect(Collectors.toMap(Room::getId, r -> r));

		Map<Long, BigDecimal> paidByResident = paymentRepository.sumByResident(pgId).stream()
				.collect(Collectors.toMap(row -> (Long) row[0], row -> (BigDecimal) row[1]));

		List<Resident> residents = residentRepository.findByPgIdOrderByName(pgId);
		int sent = 0;
		int skipped = 0;

		for (Resident r : residents) {
			if (r.getPhone() == null || r.getPhone().isBlank()) {
				skipped++;
				continue;
			}
			Room room = roomById.get(r.getRoomId());
			BigDecimal billed = billing.billed(r, room);
			BigDecimal paid = paidByResident.getOrDefault(r.getId(), BigDecimal.ZERO);
			BigDecimal due = Billing.due(billed, paid);

			if (due.signum() > 0) {
				whatsAppService.sendDueReminder(pgId, pgName, r.getName(), r.getPhone(),
						room != null ? room.getNumber() : "-", due, pgPhone);
				sent++;
			} else {
				skipped++;
			}
		}

		return new ReminderSummaryResponse(sent, skipped);
	}

	@PostMapping("/custom")
	public void sendCustom(@RequestBody SendCustomMessageRequest req) {
		Long pgId = TenantContext.requirePgId();
		if (req.to() == null || req.to().isBlank() || req.message() == null || req.message().isBlank()) {
			throw new BadRequestException("Recipient and message cannot be blank");
		}
		whatsAppService.send(pgId, req.to(), req.message());
	}

	@GetMapping("/logs")
	public List<WhatsappMessageLog> getLogs() {
		Long pgId = TenantContext.requirePgId();
		return logRepository.findTop50ByPgIdOrderByCreatedAtDesc(pgId);
	}
}
