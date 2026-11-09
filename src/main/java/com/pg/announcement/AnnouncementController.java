package com.pg.announcement;

import java.time.LocalDate;
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

import com.pg.common.NotFoundException;
import com.pg.push.PushService;
import com.pg.tenant.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Announcements for the current property: staff post and delete them, residents read them. */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

	public record AnnouncementRequest(@NotBlank @Size(max = 120) String title,
			@NotBlank @Size(max = 2000) String content, AnnouncementCategory category, Boolean important) {
	}

	public record AnnouncementResponse(Long id, String title, String content, AnnouncementCategory category,
			boolean important, LocalDate date) {
	}

	private final AnnouncementRepository repo;
	private final PushService push;

	public AnnouncementController(AnnouncementRepository repo, PushService push) {
		this.repo = repo;
		this.push = push;
	}

	@GetMapping
	public List<AnnouncementResponse> list() {
		return repo.findTop100ByPgIdOrderByIdDesc(TenantContext.requirePgId()).stream().map(this::toResponse).toList();
	}

	@PostMapping
	@Transactional
	public AnnouncementResponse add(@Valid @RequestBody AnnouncementRequest req) {
		Announcement a = new Announcement();
		a.setTitle(req.title().trim());
		a.setContent(req.content().trim());
		if (req.category() != null) {
			a.setCategory(req.category());
		}
		a.setImportant(Boolean.TRUE.equals(req.important()));
		Announcement saved = repo.save(a);
		push.toPg(TenantContext.requirePgId(), (saved.isImportant() ? "Important: " : "") + saved.getTitle(),
				saved.getContent(), java.util.Map.of("type", "ANNOUNCEMENT"));
		return toResponse(saved);
	}

	@DeleteMapping("/{id}")
	@Transactional
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		Announcement a = repo.findByPgIdAndId(TenantContext.requirePgId(), id)
				.orElseThrow(() -> new NotFoundException("Announcement not found"));
		repo.delete(a);
		return ResponseEntity.noContent().build();
	}

	private AnnouncementResponse toResponse(Announcement a) {
		LocalDate date = a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate() : LocalDate.now();
		return new AnnouncementResponse(a.getId(), a.getTitle(), a.getContent(), a.getCategory(), a.isImportant(),
				date);
	}
}
