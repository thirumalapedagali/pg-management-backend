package com.pg.complaint;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.complaint.ComplaintService.ComplaintResponse;
import com.pg.complaint.ComplaintService.ComplaintUpdate;

import jakarta.validation.Valid;

/** Owner / manager side of complaints: see what residents raised, work on it and resolve it. */
@RestController
@RequestMapping("/api/complaints")
public class ComplaintController {

	private final ComplaintService service;

	public ComplaintController(ComplaintService service) {
		this.service = service;
	}

	@GetMapping
	public List<ComplaintResponse> list() {
		return service.listAll();
	}

	@GetMapping("/count")
	public Map<String, Long> countOpen() {
		return Map.of("pending", service.countOpen());
	}

	@PutMapping("/{id}")
	public ComplaintResponse update(@PathVariable Long id, @Valid @RequestBody ComplaintUpdate req,
			Authentication auth) {
		return service.update(id, req, auth.getName());
	}
}
