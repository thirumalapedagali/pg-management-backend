package com.pg.location;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pg.common.ServiceUnavailableException;

/** Picks the geocoding provider from {@code app.geocoding.provider}: nominatim (default) | google | none. */
@Configuration
public class GeocodingConfig {

	@Bean
	public GeocodingProvider geocodingProvider(
			@Value("${app.geocoding.provider:nominatim}") String provider,
			@Value("${app.geocoding.country-codes:in}") String countryCodes,
			@Value("${app.geocoding.nominatim.base-url:https://nominatim.openstreetmap.org}") String nominatimUrl,
			@Value("${app.geocoding.nominatim.user-agent:MyStay-PG-Management/1.0}") String userAgent,
			@Value("${app.geocoding.nominatim.min-interval-ms:1100}") long minIntervalMs,
			@Value("${app.geocoding.google.base-url:https://maps.googleapis.com/maps/api/geocode/json}") String googleUrl,
			@Value("${app.geocoding.google.api-key:}") String googleKey) {
		return switch (provider.trim().toLowerCase()) {
			case "nominatim" -> new NominatimGeocodingProvider(nominatimUrl, userAgent, countryCodes, minIntervalMs);
			case "google" -> new GoogleGeocodingProvider(googleUrl, googleKey, countryCodes);
			case "none" -> (query, limit) -> {
				throw new ServiceUnavailableException("Location search is not configured (app.geocoding.provider)");
			};
			default -> throw new IllegalStateException(
					"app.geocoding.provider must be nominatim, google or none, not '" + provider + "'");
		};
	}
}
