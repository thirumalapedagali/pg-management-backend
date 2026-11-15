package com.pg.feedback;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pg.common.NotFoundException;
import com.pg.common.Paged;
import com.pg.common.UnauthorizedException;
import com.pg.pg.Pg;
import com.pg.pg.PgRepository;
import com.pg.push.PushService;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Feedback and improvement suggestions: anyone signed in can send one, the super admin reads them all. */
@Service
public class FeedbackService {

	public record FeedbackRequest(@NotNull FeedbackType type, @NotBlank @Size(max = 2000) String message) {
	}

	public record FeedbackResponse(Long id, FeedbackType type, String message, FeedbackStatus status, String username,
			String role, String pgName, LocalDateTime createdAt, LocalDateTime reviewedAt) {
	}

	private final FeedbackRepository feedback;
	private final UserRepository users;
	private final PgRepository pgs;
	private final PushService push;

	public FeedbackService(FeedbackRepository feedback, UserRepository users, PgRepository pgs, PushService push) {
		this.feedback = feedback;
		this.users = users;
		this.pgs = pgs;
		this.push = push;
	}

	@Transactional
	public FeedbackResponse submit(FeedbackRequest req, String username) {
		UserEntity user = users.findByUsername(username).orElseThrow(() -> new UnauthorizedException("Invalid token"));
		Feedback f = new Feedback();
		f.setUserId(user.getId());
		f.setUsername(user.getUsername());
		f.setRole(user.getRole().name());
		f.setPgId(user.getPgId());
		if (user.getPgId() != null) {
			f.setPgName(pgs.findById(user.getPgId()).map(Pg::getName).orElse(null));
		}
		f.setType(req.type());
		f.setMessage(req.message().trim());
		f = feedback.save(f);
		push.toSuperAdmins("New feedback", user.getUsername() + ": " + preview(f.getMessage()));
		return toResponse(f);
	}

	/** One page of feedback for a status tab (NEW, REVIEWED or ALL) and search text. */
	@Transactional(readOnly = true)
	public Paged<FeedbackResponse> list(String statusFilter, String search, int page, int size) {
		List<FeedbackStatus> statuses = List.of(FeedbackStatus.values());
		for (FeedbackStatus s : FeedbackStatus.values()) {
			if (s.name().equalsIgnoreCase(statusFilter)) {
				statuses = List.of(s);
			}
		}
		String q = search == null ? "" : search.trim().toLowerCase();
		Page<Feedback> p = feedback.search(statuses, q, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
		return new Paged<>(p.getContent().stream().map(this::toResponse).toList(), p.getNumber(), p.getSize(),
				p.getTotalElements(), p.getTotalPages());
	}

	@Transactional(readOnly = true)
	public long countNew() {
		return feedback.countByStatus(FeedbackStatus.NEW);
	}

	@Transactional
	public FeedbackResponse setStatus(Long id, FeedbackStatus status) {
		Feedback f = feedback.findById(id).orElseThrow(() -> new NotFoundException("Feedback not found"));
		f.setStatus(status);
		f.setReviewedAt(status == FeedbackStatus.REVIEWED ? LocalDateTime.now() : null);
		return toResponse(f);
	}

	private static String preview(String message) {
		return message.length() > 100 ? message.substring(0, 100) + "..." : message;
	}

	private FeedbackResponse toResponse(Feedback f) {
		return new FeedbackResponse(f.getId(), f.getType(), f.getMessage(), f.getStatus(), f.getUsername(), f.getRole(),
				f.getPgName(), f.getCreatedAt(), f.getReviewedAt());
	}
}
