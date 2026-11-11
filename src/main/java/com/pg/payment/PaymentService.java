package com.pg.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.billing.Billing;
import com.pg.notification.WhatsAppService;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.resident.Resident;
import com.pg.resident.ResidentRepository;
import com.pg.push.PushService;
import com.pg.room.Bed;
import com.pg.room.BedRepository;
import com.pg.room.Room;
import com.pg.room.RoomRepository;
import com.pg.tenant.TenantContext;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PaymentService {

	public record PaymentRequest(@NotNull Long residentId, @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
			LocalDate date, String mode, String transactionRef, String notes) {
	}

	public record ResidentPaymentRequest(@NotNull @DecimalMin(value = "0.01") BigDecimal amount,
			LocalDate date, String mode, String transactionRef, String notes) {
	}

	public record PaymentResponse(Long id, Long residentId, BigDecimal amount, LocalDate date,
			String mode, String transactionRef, String notes) {
	}

	private final PaymentRepository payments;
	private final ResidentRepository residents;
	private final RoomRepository rooms;
	private final BedRepository beds;
	private final PgRepository pgs;
	private final Billing billing;
	private final WhatsAppService whatsAppService;
	private final PushService push;
	private final AppClock clock;

	public PaymentService(PaymentRepository payments, ResidentRepository residents, RoomRepository rooms,
			BedRepository beds, PgRepository pgs, Billing billing, WhatsAppService whatsAppService, PushService push,
			AppClock clock) {
		this.push = push;
		this.payments = payments;
		this.residents = residents;
		this.rooms = rooms;
		this.beds = beds;
		this.pgs = pgs;
		this.billing = billing;
		this.whatsAppService = whatsAppService;
		this.clock = clock;
	}

	public List<PaymentResponse> list(Long residentId, LocalDate from, LocalDate to) {
		Long pgId = TenantContext.requirePgId();
		LocalDate f = from == null ? LocalDate.of(1970, 1, 1) : from;
		LocalDate t = to == null ? LocalDate.of(9999, 12, 31) : to;
		var rows = residentId == null
				? payments.findByPgIdAndDateBetweenOrderByDateDescIdDesc(pgId, f, t)
				: payments.findByPgIdAndResidentIdAndDateBetweenOrderByDateDescIdDesc(pgId, residentId, f, t);
		return rows.stream().map(PaymentService::toResponse).toList();
	}

	@Transactional
	public PaymentResponse create(PaymentRequest req) {
		Long pgId = TenantContext.requirePgId();
		Resident resident = residents.findByPgIdAndId(pgId, req.residentId())
				.orElseThrow(() -> new BadRequestException("Resident not found"));
		LocalDate date = req.date() == null ? clock.today() : req.date();
		if (date.isAfter(clock.today())) {
			throw new BadRequestException("Payment date cannot be in the future");
		}
		Payment p = new Payment();
		p.setResidentId(req.residentId());
		p.setAmount(req.amount());
		p.setDate(date);
		p.setMode(req.mode() != null && !req.mode().isBlank() ? req.mode() : "UPI");
		p.setTransactionRef(req.transactionRef());
		p.setNotes(req.notes());
		Payment saved = payments.save(p);

		push.toResident(pgId, req.residentId(), "Payment received",
				"We received Rs. " + saved.getAmount().stripTrailingZeros().toPlainString() + " on "+ saved.getDate() + ". Thank you!",
				java.util.Map.of("type", "PAYMENT"));
		try {
			push.toStaff(pgId, "Payment Update", "Payment of Rs. " + saved.getAmount().stripTrailingZeros().toPlainString() + " recorded for " + push.formatResidentDetails(pgId, resident) + ".");
		} catch (Exception e) {
			log.warn("Failed to send payment push notification to staff: {}", e.getMessage());
		}

		// Send WhatsApp Payment Receipt safely
		try {
			Room room = rooms.findByPgIdAndId(pgId, resident.getRoomId()).orElse(null);
			BigDecimal totalBilled = billing.billed(resident, room);
			BigDecimal totalPaid = payments.sumForResident(pgId, resident.getId());
			BigDecimal remainingDue = Billing.due(totalBilled, totalPaid);
			Pg pg = pgs.findById(pgId).orElse(null);
			String pgName = pg != null ? pg.getName() : "PG Management";

			whatsAppService.sendPaymentReceipt(pgId, pgName, resident.getName(), resident.getPhone(),
					room != null ? room.getNumber() : "-", saved.getAmount(), remainingDue, saved.getDate());
		} catch (Exception ex) {
			log.warn("Failed to trigger WhatsApp receipt for payment ID {}: {}", saved.getId(), ex.getMessage());
		}

		return toResponse(saved);
	}

	@Transactional
	public PaymentResponse recordResidentPayment(Long residentId, ResidentPaymentRequest req) {
		Long pgId = TenantContext.requirePgId();
		Resident resident = residents.findByPgIdAndId(pgId, residentId)
				.orElseThrow(() -> new BadRequestException("Resident not found"));
		LocalDate date = req.date() == null ? clock.today() : req.date();

		Payment p = new Payment();
		p.setResidentId(residentId);
		p.setAmount(req.amount());
		p.setDate(date);
		p.setMode(req.mode() != null && !req.mode().isBlank() ? req.mode() : "UPI");
		p.setTransactionRef(req.transactionRef());
		p.setNotes(req.notes());
		Payment saved = payments.save(p);

		try {
			push.toResident(pgId, residentId, "Payment Submitted",
					"Your payment of Rs. " + saved.getAmount().stripTrailingZeros().toPlainString() + " on " + saved.getDate() + " has been submitted successfully.",
					java.util.Map.of("type", "PAYMENT"));
			push.toStaff(pgId, "New Payment Alert",
					push.formatResidentDetails(pgId, resident) + " submitted a payment of Rs. " + saved.getAmount().stripTrailingZeros().toPlainString() + ".");
		} catch (Exception e) {
			log.warn("Failed to send payment push notifications: {}", e.getMessage());
		}

		// Send WhatsApp Payment Receipt to resident & alert to Owner
		try {
			Room room = rooms.findByPgIdAndId(pgId, resident.getRoomId()).orElse(null);
			BigDecimal totalBilled = billing.billed(resident, room);
			BigDecimal totalPaid = payments.sumForResident(pgId, resident.getId());
			BigDecimal remainingDue = Billing.due(totalBilled, totalPaid);
			Pg pg = pgs.findById(pgId).orElse(null);
			String pgName = pg != null ? pg.getName() : "PG Management";

			whatsAppService.sendPaymentReceipt(pgId, pgName, resident.getName(), resident.getPhone(),
					room != null ? room.getNumber() : "-", saved.getAmount(), remainingDue, saved.getDate());

			// Notify PG Owner via WhatsApp
			if (pg != null && pg.getMobile() != null && !pg.getMobile().isBlank()) {
				String bedNum = "-";
				if (resident.getBedId() != null) {
					bedNum = beds.findByPgIdAndId(pgId, resident.getBedId()).map(Bed::getBedNumber).orElse("-");
				}
				String utrInfo = (req.transactionRef() != null && !req.transactionRef().isBlank())
						? "\n🔢 *UTR/Ref No:* " + req.transactionRef().trim()
						: "";
				String noteInfo = (req.notes() != null && !req.notes().isBlank())
						? "\n📝 *Note:* " + req.notes().trim()
						: "";
				String ownerMsg = String.format(
						"💰 *Payment Received Alert - %s*\n\n"
						+ "Resident: *%s*\n"
						+ "Room: *%s* (Bed: %s)\n"
						+ "💵 *Amount:* ₹%s\n"
						+ "💳 *Mode:* %s%s%s\n"
						+ "📅 *Date:* %s\n"
						+ "📊 *Pending Balance:* ₹%s",
						pgName,
						resident.getName(),
						room != null ? room.getNumber() : "-",
						bedNum,
						saved.getAmount().toPlainString(),
						saved.getMode(),
						utrInfo,
						noteInfo,
						saved.getDate(),
						remainingDue.toPlainString()
				);
				whatsAppService.send(pgId, pg.getMobile(), ownerMsg);
			}
		} catch (Exception ex) {
			log.warn("Failed to trigger WhatsApp receipt/owner notification for payment ID {}: {}", saved.getId(), ex.getMessage());
		}

		return toResponse(saved);
	}

	@Transactional
	public void delete(Long id) {
		Payment p = payments.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Payment not found"));
		payments.delete(p);
	}

	static PaymentResponse toResponse(Payment p) {
		return new PaymentResponse(p.getId(), p.getResidentId(), p.getAmount(), p.getDate(),
				p.getMode(), p.getTransactionRef(), p.getNotes());
	}
}
