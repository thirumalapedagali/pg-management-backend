package com.pg.expense;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.tenant.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Expense categories (same route as the Agro app: /api/master/expenses). */
@RestController
@RequestMapping("/api/master/expenses")
public class MasterController {

	public record CategoryRequest(@NotBlank @Size(max = 60) String name) {
	}

	public record CategoryResponse(Long id, String name) {
	}

	private final ExpenseCategoryRepository repo;

	public MasterController(ExpenseCategoryRepository repo) {
		this.repo = repo;
	}

	@GetMapping
	public List<CategoryResponse> list() {
		return repo.findByPgIdOrderByName(TenantContext.requirePgId()).stream()
				.map(c -> new CategoryResponse(c.getId(), c.getName())).toList();
	}

	@PostMapping
	@Transactional
	public CategoryResponse add(@Valid @RequestBody CategoryRequest req) {
		Long pgId = TenantContext.requirePgId();
		String name = req.name().trim();
		if (repo.existsByPgIdAndNameIgnoreCase(pgId, name)) {
			throw new BadRequestException("Category already exists: " + name);
		}
		ExpenseCategory c = new ExpenseCategory();
		c.setName(name);
		c = repo.save(c);
		return new CategoryResponse(c.getId(), c.getName());
	}

	@DeleteMapping("/{id}")
	@Transactional
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		ExpenseCategory c = repo.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Category not found"));
		repo.delete(c);
		return ResponseEntity.noContent().build();
	}
}
