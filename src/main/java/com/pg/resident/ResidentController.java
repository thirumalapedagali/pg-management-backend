package com.pg.resident;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.resident.ResidentService.MoveResidentRequest;
import com.pg.resident.ResidentService.ResidentRequest;
import com.pg.resident.ResidentService.ResidentResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/residents")
public class ResidentController {

	private final ResidentService service;

	public ResidentController(ResidentService service) {
		this.service = service;
	}

	@GetMapping
	public List<ResidentResponse> list(@org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "ACTIVE") String status) {
		return service.list(status);
	}

	/** Notice-to-vacate requests waiting for the owner (dashboard badge). */
	@GetMapping("/notice-count")
	public java.util.Map<String, Long> noticeCount() {
		return java.util.Map.of("pending", service.pendingNoticeCount());
	}

	@GetMapping("/{id}")
	public ResidentResponse get(@PathVariable Long id) {
		return service.get(id);
	}

	@PostMapping
	public ResidentResponse create(@Valid @RequestBody ResidentRequest req) {
		if (req.phone() == null || req.phone().isBlank()) {
			throw new com.pg.common.BadRequestException("Mobile number is required");
		}
		if (req.aadhaar() == null || req.aadhaar().isBlank()) {
			throw new com.pg.common.BadRequestException("Aadhaar number is required");
		}
		return service.create(req);
	}

	@PutMapping("/{id}")
	public ResidentResponse update(@PathVariable Long id, @Valid @RequestBody ResidentRequest req) {
		return service.update(id, req);
	}

	@PostMapping("/{id}/move")
	public ResidentResponse move(@PathVariable Long id, @Valid @RequestBody MoveResidentRequest req) {
		return service.move(id, req);
	}

	@PostMapping("/{id}/vacate-bed")
	public ResidentResponse vacateBed(@PathVariable Long id) {
		return service.vacateBed(id);
	}

	@PostMapping("/{id}/vacate")
	public ResidentResponse vacate(@PathVariable Long id,
			@org.springframework.web.bind.annotation.RequestParam(required = false) java.time.LocalDate checkOutDate,
			@org.springframework.web.bind.annotation.RequestParam(required = false) java.time.LocalDateTime checkOutTime) {
		return service.vacate(id, checkOutDate, checkOutTime);
	}

	/** Owner decision on a resident's notice to vacate. */
	@PostMapping("/{id}/notice/approve")
	public ResidentResponse approveNotice(@PathVariable Long id) {
		return service.decideNotice(id, true);
	}

	@PostMapping("/{id}/notice/reject")
	public ResidentResponse rejectNotice(@PathVariable Long id) {
		return service.decideNotice(id, false);
	}

	@DeleteMapping("/{id}/notice")
	public ResidentResponse cancelNotice(@PathVariable Long id) {
		return service.cancelNotice(id);
	}

	@PostMapping("/{id}/rejoin")
	public ResidentResponse rejoin(@PathVariable Long id, @Valid @RequestBody ResidentService.RejoinResidentRequest req) {
		return service.rejoin(id, req);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
