package com.pg.support;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.NotFoundException;
import com.pg.tenant.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Support contacts for the current property: staff manage them, residents read them. */
@RestController
@RequestMapping("/api/support-contacts")
public class SupportContactController {

	public record ContactRequest(@NotBlank @Size(max = 80) String name,
			@NotBlank @Pattern(regexp = "^\\+?\\d{3,15}$", message = "Enter a valid phone number") String phone,
			@Size(max = 160) String note) {
	}

	public record ContactResponse(Long id, String name, String phone, String note) {
	}

	private final SupportContactRepository repo;

	public SupportContactController(SupportContactRepository repo) {
		this.repo = repo;
	}

	@GetMapping
	public List<ContactResponse> list() {
		return repo.findByPgIdOrderByIdAsc(TenantContext.requirePgId()).stream().map(this::toResponse).toList();
	}

	@PostMapping
	@Transactional
	public ContactResponse add(@Valid @RequestBody ContactRequest req) {
		return toResponse(repo.save(apply(new SupportContact(), req)));
	}

	@PutMapping("/{id}")
	@Transactional
	public ContactResponse update(@PathVariable Long id, @Valid @RequestBody ContactRequest req) {
		return toResponse(repo.save(apply(find(id), req)));
	}

	@DeleteMapping("/{id}")
	@Transactional
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		repo.delete(find(id));
		return ResponseEntity.noContent().build();
	}

	private SupportContact find(Long id) {
		return repo.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Contact not found"));
	}

	private SupportContact apply(SupportContact c, ContactRequest req) {
		c.setName(req.name().trim());
		c.setPhone(req.phone().trim());
		String note = req.note() == null ? "" : req.note().trim();
		c.setNote(note.isEmpty() ? null : note);
		return c;
	}

	private ContactResponse toResponse(SupportContact c) {
		return new ContactResponse(c.getId(), c.getName(), c.getPhone(), c.getNote());
	}
}
