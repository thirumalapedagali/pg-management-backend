package com.pg.location;

import java.util.List;

/**
 * Turns free text such as "Marathahalli, Bengaluru" into coordinates. Business code only sees this interface, so the
 * provider is chosen with {@code app.geocoding.provider} (nominatim | google | none) and nothing else changes.
 * Implementations throw {@link com.pg.common.ServiceUnavailableException} when the provider cannot be used.
 */
public interface GeocodingProvider {

	/** Best matches first; empty when nothing was found. */
	List<GeoLocation> search(String query, int limit);
}
