package com.pg.pg;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.pg.PgService.PgResponse;
import com.pg.pg.PgService.UpdatePgProfileRequest;
import com.pg.tenant.TenantContext;

import jakarta.validation.Valid;

/** Endpoints for PG Owners/Managers to view & update their PG settings, UPI ID, etc. */
@RestController
@RequestMapping("/api/pg/profile")
public class PgProfileController {

	private final PgService service;

	public PgProfileController(PgService service) {
		this.service = service;
	}

	@GetMapping
	public PgResponse get() {
		return service.getProfile(TenantContext.requirePgId());
	}

	@PutMapping
	public PgResponse update(@Valid @RequestBody UpdatePgProfileRequest req) {
		return service.updateProfile(TenantContext.requirePgId(), req);
	}
}
