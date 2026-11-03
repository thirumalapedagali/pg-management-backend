package com.pg.resident;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.NotFoundException;
import com.pg.payment.Payment;
import com.pg.payment.PaymentRepository;
import com.pg.payment.PaymentService;
import com.pg.payment.PaymentService.PaymentResponse;
import com.pg.payment.PaymentService.ResidentPaymentRequest;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.resident.ResidentService.ResidentResponse;
import com.pg.tenant.TenantContext;
import com.pg.user.UserRepository;

import jakarta.validation.Valid;

/** Resident self-service ("My Dues"). Always resolves to the caller's own resident row, never a path id. */
@RestController
@RequestMapping("/api/me")
public class MeController {

	public record PgInfo(Long id, String name, String stayType, String unitLabel, String locationUrl, String genderType, String upiId, String upiName, String ownerMobile, String ownerEmail) {
	}

	public record MyPayment(Long id, BigDecimal amount, LocalDate date, String mode, String transactionRef, String notes) {
	}

	public record MyDues(ResidentResponse resident, List<MyPayment> payments, PgInfo pg) {
	}

	private final UserRepository users;
	private final ResidentService residents;
	private final PaymentRepository payments;
	private final PaymentService paymentService;
	private final PgRepository pgs;

	public MeController(UserRepository users, ResidentService residents, PaymentRepository payments,
			PaymentService paymentService, PgRepository pgs) {
		this.users = users;
		this.residents = residents;
		this.payments = payments;
		this.paymentService = paymentService;
		this.pgs = pgs;
	}

	@GetMapping("/dues")
	public MyDues dues(Authentication auth) {
		Long residentId = users.findByUsername(auth.getName()).map(u -> u.getResidentId())
				.orElseThrow(() -> new NotFoundException("No resident profile linked to this login"));
		ResidentResponse r = residents.get(residentId);
		Long pgId = TenantContext.requirePgId();
		List<MyPayment> list = payments.findByPgIdAndResidentIdOrderByDateDescIdDesc(pgId, residentId)
				.stream().map((Payment p) -> new MyPayment(p.getId(), p.getAmount(), p.getDate(), p.getMode(), p.getTransactionRef(), p.getNotes())).toList();
		Pg pg = pgs.findById(pgId).orElse(null);
		PgInfo pgInfo = pg != null ? new PgInfo(pg.getId(), pg.getName(), pg.getStayType() != null ? pg.getStayType() : "PG", pg.getUnitLabel() != null ? pg.getUnitLabel() : "Room", pg.getLocationUrl(), pg.getGenderType(), pg.getUpiId(), pg.getUpiName(), pg.getMobile(), pg.getEmail()) : null;
		return new MyDues(r, list, pgInfo);
	}

	@PostMapping("/payments")
	public PaymentResponse recordPayment(Authentication auth, @Valid @RequestBody ResidentPaymentRequest req) {
		Long residentId = users.findByUsername(auth.getName()).map(u -> u.getResidentId())
				.orElseThrow(() -> new NotFoundException("No resident profile linked to this login"));
		return paymentService.recordResidentPayment(residentId, req);
	}
}
