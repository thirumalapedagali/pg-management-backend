package com.pg.expense;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.Paged;
import com.pg.common.PeriodTotals;
import com.pg.expense.ExpenseService.ExpenseRequest;
import com.pg.expense.ExpenseService.ExpenseResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

	private final ExpenseService service;

	public ExpenseController(ExpenseService service) {
		this.service = service;
	}

	@GetMapping
	public Paged<ExpenseResponse> list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String category, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return service.search(from, to, category, q, page, size);
	}

	/** {today, month, year, total} */
	@GetMapping("/summary")
	public PeriodTotals summary() {
		return service.summary();
	}

	@PostMapping
	public ExpenseResponse create(@Valid @RequestBody ExpenseRequest req) {
		return service.create(req);
	}

	@PutMapping("/{id}")
	public ExpenseResponse update(@PathVariable Long id, @Valid @RequestBody ExpenseRequest req) {
		return service.update(id, req);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
