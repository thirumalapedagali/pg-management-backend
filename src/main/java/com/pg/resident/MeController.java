package com.pg.resident;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.NotFoundException;
import com.pg.payment.Payment;
import com.pg.payment.PaymentRepository;
import com.pg.resident.ResidentService.ResidentResponse;
import com.pg.tenant.TenantContext;
import com.pg.user.UserRepository;

/** Resident self-service ("My Dues"). Always resolves to the caller's own resident row, never a path id. */
@RestController
@RequestMapping("/api/me")
public class MeController {

	public record MyPayment(Long id, BigDecimal amount, LocalDate date) {
	}

	public record MyDues(ResidentResponse resident, List<MyPayment> payments) {
	}

	private final UserRepository users;
	private final ResidentService residents;
	private final PaymentRepository payments;

	public MeController(UserRepository users, ResidentService residents, PaymentRepository payments) {
		this.users = users;
		this.residents = residents;
		this.payments = payments;
	}

	@GetMapping("/dues")
	public MyDues dues(Authentication auth) {
		Long residentId = users.findByUsername(auth.getName()).map(u -> u.getResidentId())
				.orElseThrow(() -> new NotFoundException("No resident profile linked to this login"));
		ResidentResponse r = residents.get(residentId);
		List<MyPayment> list = payments.findByPgIdAndResidentIdOrderByDateDescIdDesc(TenantContext.requirePgId(), residentId)
				.stream().map((Payment p) -> new MyPayment(p.getId(), p.getAmount(), p.getDate())).toList();
		return new MyDues(r, list);
	}
}
