package com.pg.common;

/** A dependency (geocoding provider, PostGIS) is missing or down. Mapped to HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {
	public ServiceUnavailableException(String message) {
		super(message);
	}
}
