package com.pg.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.AppClock;
import com.pg.common.NotFoundException;
import com.pg.common.Paged;
import com.pg.common.PeriodTotals;
import com.pg.tenant.TenantContext;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class ExpenseService {

	public record ExpenseRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 60) String category,
			@NotNull @DecimalMin("0.01") BigDecimal amount, LocalDate date) {
	}

	public record ExpenseResponse(Long id, String title, String category, BigDecimal amount, LocalDate date) {
	}

	private final ExpenseRepository repo;
	private final AppClock clock;

	public ExpenseService(ExpenseRepository repo, AppClock clock) {
		this.repo = repo;
		this.clock = clock;
	}

	public Paged<ExpenseResponse> search(LocalDate from, LocalDate to, String category, String q, int page, int size) {
		Long pgId = TenantContext.requirePgId();
		Specification<Expense> spec = (root, query, cb) -> {
			List<Predicate> p = new ArrayList<>();
			p.add(cb.equal(root.get("pgId"), pgId));
			if (from != null) {
				p.add(cb.greaterThanOrEqualTo(root.get("date"), from));
			}
			if (to != null) {
				p.add(cb.lessThanOrEqualTo(root.get("date"), to));
			}
			if (category != null && !category.isBlank()) {
				p.add(cb.equal(root.get("category"), category));
			}
			if (q != null && !q.isBlank()) {
				String like = "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
				p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'),
						cb.like(cb.lower(root.get("category")), like, '\\')));
			}
			return cb.and(p.toArray(Predicate[]::new));
		};
		var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
				Sort.by(Sort.Direction.DESC, "date").and(Sort.by(Sort.Direction.DESC, "id")));
		return Paged.of(repo.findAll(spec, pageable).map(ExpenseService::toResponse));
	}

	@Transactional
	public ExpenseResponse create(ExpenseRequest req) {
		Expense e = new Expense();
		apply(e, req);
		return toResponse(repo.save(e));
	}

	@Transactional
	public ExpenseResponse update(Long id, ExpenseRequest req) {
		Expense e = find(id);
		apply(e, req);
		return toResponse(e);
	}

	@Transactional
	public void delete(Long id) {
		repo.delete(find(id));
	}

	public PeriodTotals summary() {
		Long pgId = TenantContext.requirePgId();
		return PeriodTotals.compute(clock.today(), (a, b) -> repo.sumBetween(pgId, a, b), () -> repo.sumAll(pgId));
	}

	private Expense find(Long id) {
		return repo.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Expense not found"));
	}

	private void apply(Expense e, ExpenseRequest req) {
		e.setTitle(req.title().trim());
		e.setCategory(req.category().trim());
		e.setAmount(req.amount());
		e.setDate(req.date() == null ? clock.today() : req.date());
	}

	static ExpenseResponse toResponse(Expense e) {
		return new ExpenseResponse(e.getId(), e.getTitle(), e.getCategory(), e.getAmount(), e.getDate());
	}
}
