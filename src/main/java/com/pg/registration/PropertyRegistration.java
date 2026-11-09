package com.pg.registration;

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

/**
 * An owner's request to put a property on the platform. Nothing exists for it (no property, no login) until the super
 * admin approves it; the chosen password is kept only as a BCrypt hash.
 */
@Entity
@Table(name = "property_registrations")
@Getter
@Setter
@NoArgsConstructor
public class PropertyRegistration {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String propertyName;

	@Column(nullable = false, length = 120)
	private String ownerName;

	/** The owner's login (username and mobile). */
	@Column(nullable = false, length = 15)
	private String mobile;

	@Column(nullable = false)
	private String passwordHash;

	/** The submitted form (everything except the password) as JSON; read back when the request is approved. */
	@Column(nullable = false, length = 10000)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "reg_status", nullable = false)
	private RegistrationStatus status = RegistrationStatus.PENDING;

	@Column(length = 500)
	private String rejectionReason;

	/** The property created on approval. */
	private Long pgId;

	@Column(updatable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	private LocalDateTime decidedAt;
}
