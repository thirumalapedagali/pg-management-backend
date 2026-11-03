package com.pg.pg;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.pg.PgService.PgRequest;
import com.pg.pg.PgService.PgResponse;
import com.pg.pg.PgService.UpdatePgProfileRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** SUPER_ADMIN only (see SecurityConfig). The Shop Management screen maps onto this. */
@RestController
@RequestMapping("/api/admin/pgs")
public class AdminPgController {

	private final PgService service;

	public AdminPgController(PgService service) {
		this.service = service;
	}

	@PostMapping
	public PgResponse create(@Valid @RequestBody PgRequest request) {
		return service.create(request);
	}

	@GetMapping
	public List<PgResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	public PgResponse get(@PathVariable Long id) {
		return service.getProfile(id);
	}

	@PutMapping("/{id}")
	public PgResponse update(@PathVariable Long id, @Valid @RequestBody UpdatePgProfileRequest request) {
		return service.updateProfile(id, request);
	}

	@PutMapping("/{id}/status")
	public void status(@PathVariable Long id, @RequestParam boolean active) {
		service.setActive(id, active);
	}

	@PutMapping("/{id}/license")
	public void license(@PathVariable Long id, @RequestParam @Min(1) @Max(120) int months) {
		service.extendLicense(id, months);
	}
}
