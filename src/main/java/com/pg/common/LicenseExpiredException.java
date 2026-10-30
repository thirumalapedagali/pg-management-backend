package com.pg.common;

public class LicenseExpiredException extends RuntimeException {
	public LicenseExpiredException(String message) {
		super(message);
	}
}
