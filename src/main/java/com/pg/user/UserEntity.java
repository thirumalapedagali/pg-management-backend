package com.pg.user;

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

@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Null only for SUPER_ADMIN. */
	@Column(name = "pg_id")
	private Long pgId;

	/** Set for RESIDENT users: the resident row this login belongs to. */
	private Long residentId;

	@Column(nullable = false, unique = true)
	private String username;

	@Column
	private String email;

	@Column(nullable = false, unique = true, length = 15)
	private String mobile;

	/** BCrypt hash. */
	@Column(nullable = false)
	private String password;

	@Enumerated(EnumType.STRING)
	@Column(name = "user_role", nullable = false)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(name = "user_status", nullable = false)
	private Status status = Status.ACTIVE;

	private boolean mustChangePassword;

	private LocalDateTime lastLoginAt;

	@Column(updatable = false)
	private LocalDateTime createdAt = LocalDateTime.now();
}
