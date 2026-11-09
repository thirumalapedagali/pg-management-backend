package com.pg.registration;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.registration.PropertyRegistrationService.RegistrationItem;
import com.pg.registration.PropertyRegistrationService.SubmitRequest;

import jakarta.validation.Valid;

/**
 * Public: an owner asks to join ({@code /api/public/owner-registrations}). Super admin only (see SecurityConfig):
 * list, approve and reject those requests ({@code /api/admin/registrations}).
 */
@RestController
public class PropertyRegistrationController {

	public record RejectRequest(String reason) {
	}

	private final PropertyRegistrationService service;

	public PropertyRegistrationController(PropertyRegistrationService service) {
		this.service = service;
	}

	@PostMapping("/api/public/owner-registrations")
	public Map<String, String> submit(@Valid @RequestBody SubmitRequest req) {
		service.submit(req);
		return Map.of("message", "Request received. You can sign in once the administrator approves it.");
	}

	@GetMapping("/api/admin/registrations")
	public List<RegistrationItem> list(@RequestParam(required = false) RegistrationStatus status) {
		return service.list(status);
	}

	@PostMapping("/api/admin/registrations/{id}/approve")
	public RegistrationItem approve(@PathVariable Long id) {
		return service.approve(id);
	}

	@PostMapping("/api/admin/registrations/{id}/reject")
	public RegistrationItem reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest req) {
		return service.reject(id, req == null ? null : req.reason());
	}
}
