package com.pg.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.AppClock;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.resident.ResidentRepository;
import com.pg.tenant.TenantContext;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

@Service
public class PaymentService {

	public record PaymentRequest(@NotNull Long residentId, @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
			LocalDate date) {
	}

	public record PaymentResponse(Long id, Long residentId, BigDecimal amount, LocalDate date) {
	}

	private final PaymentRepository payments;
	private final ResidentRepository residents;
	private final AppClock clock;

	public PaymentService(PaymentRepository payments, ResidentRepository residents, AppClock clock) {
		this.payments = payments;
		this.residents = residents;
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
		residents.findByPgIdAndId(pgId, req.residentId()).orElseThrow(() -> new BadRequestException("Resident not found"));
		LocalDate date = req.date() == null ? clock.today() : req.date();
		if (date.isAfter(clock.today())) {
			throw new BadRequestException("Payment date cannot be in the future");
		}
		Payment p = new Payment();
		p.setResidentId(req.residentId());
		p.setAmount(req.amount());
		p.setDate(date);
		return toResponse(payments.save(p));
	}

	@Transactional
	public void delete(Long id) {
		Payment p = payments.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Payment not found"));
		payments.delete(p);
	}

	static PaymentResponse toResponse(Payment p) {
		return new PaymentResponse(p.getId(), p.getResidentId(), p.getAmount(), p.getDate());
	}
}
