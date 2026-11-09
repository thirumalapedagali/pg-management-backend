package com.pg.location;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.pg.common.ServiceUnavailableException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenStreetMap Nominatim. Free, but its usage policy allows one request per second and requires an identifying
 * User-Agent, so every call goes through {@link #throttle()}; with the response cache in
 * {@link GeocodingService} the public instance is fine for a modest number of searches. For heavy traffic or
 * as-you-type suggestions use Google (or your own Nominatim server via {@code app.geocoding.nominatim.base-url}).
 */
public class NominatimGeocodingProvider implements GeocodingProvider {

	private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingProvider.class);
	/** Never queue a caller for longer than this; fail fast instead so request threads are not piled up. */
	private static final long MAX_WAIT_MS = 2500;

	private final ObjectMapper json = new ObjectMapper();
	private final RestClient http;
	private final String baseUrl;
	private final String countryCodes;
	private final long minIntervalMs;
	private long nextSlot;

	public NominatimGeocodingProvider(String baseUrl, String userAgent, String countryCodes, long minIntervalMs) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(4));
		factory.setReadTimeout(Duration.ofSeconds(6));
		this.http = RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", userAgent)
				.defaultHeader("Accept-Language", "en").build();
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.countryCodes = countryCodes == null ? "" : countryCodes.trim();
		this.minIntervalMs = minIntervalMs;
	}

	@Override
	public List<GeoLocation> search(String query, int limit) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + "/search").queryParam("q", query)
				.queryParam("format", "jsonv2").queryParam("addressdetails", 1).queryParam("limit", limit);
		if (!countryCodes.isEmpty()) {
			uri.queryParam("countrycodes", countryCodes);
		}
		URI target = uri.build().encode().toUri();
		throttle();
		String body;
		try {
			body = http.get().uri(target).retrieve().body(String.class);
		} catch (RestClientException e) {
			log.warn("Nominatim request failed: {}", e.getMessage());
			throw new ServiceUnavailableException("Location search is temporarily unavailable. Please try again.");
		}
		return parse(body);
	}

	List<GeoLocation> parse(String body) {
		List<GeoLocation> out = new ArrayList<>();
		if (body == null || body.isBlank()) {
			return out;
		}
		JsonNode root;
		try {
			root = json.readTree(body);
		} catch (RuntimeException e) {
			throw new ServiceUnavailableException("Location search returned an unreadable answer");
		}
		for (JsonNode item : root) {
			double lat = item.path("lat").asDouble(Double.NaN);
			double lon = item.path("lon").asDouble(Double.NaN);
			if (Double.isNaN(lat) || Double.isNaN(lon)) {
				continue;
			}
			JsonNode a = item.path("address");
			String locality = first(a, "suburb", "city_district", "neighbourhood", "quarter", "village");
			String sub = first(a, "neighbourhood", "quarter", "residential", "hamlet");
			if (sub != null && sub.equals(locality)) {
				sub = null;
			}
			String city = first(a, "city", "town", "municipality", "state_district", "county");
			out.add(new GeoLocation(item.path("display_name").asString(""), lat, lon, locality, sub, city,
					first(a, "state"), first(a, "postcode"), first(a, "country")));
		}
		return out;
	}

	private static String first(JsonNode node, String... keys) {
		for (String k : keys) {
			String v = node.path(k).asString("");
			if (!v.isBlank()) {
				return v.trim();
			}
		}
		return null;
	}

	/** Spaces calls at least {@code minIntervalMs} apart; fails fast when the queue is already too long. */
	private void throttle() {
		long wait;
		synchronized (this) {
			long now = System.currentTimeMillis();
			long slot = Math.max(now, nextSlot);
			wait = slot - now;
			if (wait > MAX_WAIT_MS) {
				throw new ServiceUnavailableException("Location search is busy. Please try again in a moment.");
			}
			nextSlot = slot + minIntervalMs;
		}
		if (wait > 0) {
			try {
				Thread.sleep(wait);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ServiceUnavailableException("Location search was interrupted");
			}
		}
	}
}
