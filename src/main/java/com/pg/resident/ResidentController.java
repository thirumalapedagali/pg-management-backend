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
	public List<ResidentResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	public ResidentResponse get(@PathVariable Long id) {
		return service.get(id);
	}

	@PostMapping
	public ResidentResponse create(@Valid @RequestBody ResidentRequest req) {
		return service.create(req);
	}

	@PutMapping("/{id}")
	public ResidentResponse update(@PathVariable Long id, @Valid @RequestBody ResidentRequest req) {
		return service.update(id, req);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
