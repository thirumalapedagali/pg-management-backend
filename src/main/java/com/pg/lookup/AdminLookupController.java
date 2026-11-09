package com.pg.lookup;

import java.util.List;
import java.util.Locale;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Super admin maintenance of the lookup table. Any category name is allowed, so a new kind of dropdown needs no code
 * change to store its options. Category and code are fixed once created (other records store the code); an option
 * is retired by switching it inactive rather than deleting it.
 */
@RestController
@RequestMapping("/api/admin/lookups")
public class AdminLookupController {

	private static final String KEY = "^[A-Za-z][A-Za-z0-9_]*$";

	public record CreateRequest(@NotBlank @Size(max = 40) @Pattern(regexp = KEY, message = "Use letters, digits and _ only") String category,
			@NotBlank @Size(max = 40) @Pattern(regexp = KEY, message = "Use letters, digits and _ only") String code,
			@NotBlank @Size(max = 80) String label, @Size(max = 80) String extra, Integer sortOrder) {
	}

	public record UpdateRequest(@Size(max = 80) String label, @Size(max = 80) String extra, Integer sortOrder,
			Boolean active) {
	}

	public record LookupRow(Long id, String category, String code, String label, String extra, int sortOrder,
			boolean active) {
	}

	private final LookupValueRepository repo;

	public AdminLookupController(LookupValueRepository repo) {
		this.repo = repo;
	}

	@GetMapping
	public List<LookupRow> list() {
		return repo.findAllByOrderByCategoryAscSortOrderAscLabelAsc().stream().map(this::toRow).toList();
	}

	@PostMapping
	@Transactional
	public LookupRow add(@Valid @RequestBody CreateRequest req) {
		String category = req.category().trim().toUpperCase(Locale.ROOT);
		String code = req.code().trim().toUpperCase(Locale.ROOT);
		if (repo.findByCategoryAndCode(category, code).isPresent()) {
			throw new BadRequestException(code + " already exists in " + category);
		}
		LookupValue v = new LookupValue(category, code, req.label().trim(), blankToNull(req.extra()),
				req.sortOrder() != null ? req.sortOrder() : 0);
		return toRow(repo.save(v));
	}

	@PutMapping("/{id}")
	@Transactional
	public LookupRow update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
		LookupValue v = repo.findById(id).orElseThrow(() -> new NotFoundException("Lookup value not found"));
		if (req.label() != null) {
			if (req.label().isBlank()) {
				throw new BadRequestException("Label cannot be empty");
			}
			v.setLabel(req.label().trim());
		}
		if (req.extra() != null) {
			v.setExtra(blankToNull(req.extra()));
		}
		if (req.sortOrder() != null) {
			v.setSortOrder(req.sortOrder());
		}
		if (req.active() != null) {
			v.setActive(req.active());
		}
		return toRow(repo.save(v));
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private LookupRow toRow(LookupValue v) {
		return new LookupRow(v.getId(), v.getCategory(), v.getCode(), v.getLabel(), v.getExtra(), v.getSortOrder(),
				v.isActive());
	}
}
