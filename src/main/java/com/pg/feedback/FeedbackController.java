package com.pg.feedback;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pg.common.Paged;
import com.pg.feedback.FeedbackService.FeedbackRequest;
import com.pg.feedback.FeedbackService.FeedbackResponse;

import jakarta.validation.Valid;

/** Anyone signed in sends feedback; the SUPER_ADMIN-only {@code /api/admin/feedback} side reads it (see SecurityConfig). */
@RestController
public class FeedbackController {

	private final FeedbackService service;

	public FeedbackController(FeedbackService service) {
		this.service = service;
	}

	@PostMapping("/api/feedback")
	public FeedbackResponse submit(@Valid @RequestBody FeedbackRequest req, Authentication auth) {
		return service.submit(req, auth.getName());
	}

	@GetMapping("/api/admin/feedback")
	public Paged<FeedbackResponse> list(@RequestParam(required = false, defaultValue = "NEW") String status,
			@RequestParam(required = false) String search, @RequestParam(required = false, defaultValue = "0") int page,
			@RequestParam(required = false, defaultValue = "20") int size) {
		return service.list(status, search, page, size);
	}

	@GetMapping("/api/admin/feedback/count")
	public Map<String, Long> countNew() {
		return Map.of("pending", service.countNew());
	}

	@PutMapping("/api/admin/feedback/{id}")
	public FeedbackResponse setStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
		return service.setStatus(id, FeedbackStatus.valueOf(body.getOrDefault("status", "REVIEWED")));
	}
}
