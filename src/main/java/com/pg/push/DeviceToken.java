package com.pg.push;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An FCM registration token of one phone, tied to the user who is signed in on it. A token belongs to one user at a time:
 * when someone else signs in on the same phone, the row is handed over to them.
 */
@Entity
@Table(name = "device_tokens",
uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_push_token", columnNames = {"user_id", "token"})
    },
    indexes = {
        @Index(name = "idx_push_token_user_id", columnList = "user_id")
    })
@Getter
@Setter
@NoArgsConstructor
public class DeviceToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false, length = 400)
	private String token;

	@Column(length = 20)
	private String platform;

	@Column(nullable = false)
	private LocalDateTime updatedAt = LocalDateTime.now();
}
