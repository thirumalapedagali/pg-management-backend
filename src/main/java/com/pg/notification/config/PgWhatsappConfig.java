package com.pg.notification.config;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-PG WhatsApp Cloud API credentials and notification preferences. */
@Entity
@Table(name = "pg_whatsapp_config")
@Getter
@Setter
@NoArgsConstructor
public class PgWhatsappConfig {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pg_id", nullable = false, unique = true)
	private Long pgId;

	@Column(name = "phone_number_id", nullable = false, length = 100)
	private String phoneNumberId;

	@Column(name = "waba_id", length = 100)
	private String wabaId;

	@Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
	private String accessTokenEncrypted;

	@Column(name = "api_version", nullable = false, length = 10)
	private String apiVersion = "v22.0";

	@Column(nullable = false)
	private boolean enabled = false;

	@Column(name = "notify_on_payment", nullable = false)
	private boolean notifyOnPayment = true;

	@Column(name = "notify_on_due_reminder", nullable = false)
	private boolean notifyOnDueReminder = true;

	@Column(name = "notify_on_onboarding", nullable = false)
	private boolean notifyOnOnboarding = true;

	@Column(name = "notify_on_move", nullable = false)
	private boolean notifyOnMove = true;

	@Column(name = "created_at", updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	void onCreate() {
		createdAt = updatedAt = LocalDateTime.now();
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = LocalDateTime.now();
	}
}
