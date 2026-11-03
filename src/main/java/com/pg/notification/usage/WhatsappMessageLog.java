package com.pg.notification.usage;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pg_whatsapp_message_logs", indexes = {
		@Index(columnList = "pg_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class WhatsappMessageLog {

	public static final String SENT = "SENT";
	public static final String FAILED = "FAILED";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pg_id", nullable = false)
	private Long pgId;

	@Column(nullable = false, length = 50)
	private String recipient;

	@Column(name = "message_type", length = 50)
	private String messageType;

	@Column(nullable = false, length = 20)
	private String status;

	@Column(length = 500)
	private String error;

	@Column(name = "created_at", updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
