package com.pg.feedback;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** App feedback from any user. Platform-wide (read by the super admin), so it is not tied to one PG's tenant context. */
@Entity
@Table(name = "feedback")
@Getter
@Setter
@NoArgsConstructor
public class Feedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long userId;

	@Column(nullable = false)
	private String username;

	/** Role of the sender when it was sent. */
	@Column(nullable = false, length = 20)
	private String role;

	/** The sender's PG, when they have one. */
	private Long pgId;

	private String pgName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FeedbackType type = FeedbackType.SUGGESTION;

	@Column(nullable = false, length = 2000)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FeedbackStatus status = FeedbackStatus.NEW;

	@Column(updatable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	private LocalDateTime reviewedAt;
}
