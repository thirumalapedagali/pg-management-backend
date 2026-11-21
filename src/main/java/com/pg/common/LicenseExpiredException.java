package com.pg.common;

import java.time.LocalDateTime;

public class LicenseExpiredException extends RuntimeException {
	private final String role;
	private final LocalDateTime expiry;

	public LicenseExpiredException(String message) {
		this(message, null, null);
	}

	/** The role and expiry let the app show the right renewal popup (only owners can renew). */
	public LicenseExpiredException(String message, String role, LocalDateTime expiry) {
		super(message);
		this.role = role;
		this.expiry = expiry;
	}

	public String getRole() {
		return role;
	}

	public LocalDateTime getExpiry() {
		return expiry;
	}
}
