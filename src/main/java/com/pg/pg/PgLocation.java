package com.pg.pg;

import com.pg.common.BadRequestException;

/** Validates and stores where a property is. The PostGIS {@code location} column is generated from these values. */
public final class PgLocation {

	private PgLocation() {
	}

	/** Coordinates come as a pair, in WGS84 degrees. Anything else is rejected rather than stored. */
	public static void validate(Double latitude, Double longitude) {
		if (latitude == null && longitude == null) {
			return;
		}
		if (latitude == null || longitude == null) {
			throw new BadRequestException("Send both latitude and longitude");
		}
		if (latitude.isNaN() || latitude.isInfinite() || latitude < -90 || latitude > 90) {
			throw new BadRequestException("Latitude must be between -90 and 90");
		}
		if (longitude.isNaN() || longitude.isInfinite() || longitude < -180 || longitude > 180) {
			throw new BadRequestException("Longitude must be between -180 and 180");
		}
	}

	/** Leaves the property's location alone when no coordinates are sent. */
	public static void apply(Pg pg, Double latitude, Double longitude, String locality, String subLocality,
			String pincode, String country) {
		validate(latitude, longitude);
		if (latitude != null) {
			pg.setLatitude(latitude);
			pg.setLongitude(longitude);
		}
		if (locality != null) {
			pg.setLocality(blankToNull(locality));
		}
		if (subLocality != null) {
			pg.setSubLocality(blankToNull(subLocality));
		}
		if (pincode != null) {
			pg.setPincode(blankToNull(pincode));
		}
		if (country != null) {
			pg.setCountry(blankToNull(country));
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
