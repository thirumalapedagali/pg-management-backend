package com.pg.common;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** "Today" in the PG's time zone, not the server's. */
@Component
public class AppClock {

	private final ZoneId zone;

	public AppClock(@Value("${app.timezone:Asia/Kolkata}") String zone) {
		this.zone = ZoneId.of(zone);
	}

	public LocalDate today() {
		return LocalDate.now(zone);
	}

	public java.time.LocalDateTime now() {
		return java.time.LocalDateTime.now(zone);
	}
}
