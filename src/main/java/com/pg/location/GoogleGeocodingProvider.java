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

/** Google Maps Geocoding API. Needs {@code app.geocoding.google.api-key} (Geocoding API enabled, billing on). */
public class GoogleGeocodingProvider implements GeocodingProvider {

	private static final Logger log = LoggerFactory.getLogger(GoogleGeocodingProvider.class);

	private final ObjectMapper json = new ObjectMapper();
	private final RestClient http;
	private final String baseUrl;
	private final String apiKey;
	private final String countryCodes;

	public GoogleGeocodingProvider(String baseUrl, String apiKey, String countryCodes) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("app.geocoding.provider=google needs app.geocoding.google.api-key");
		}
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(4));
		factory.setReadTimeout(Duration.ofSeconds(6));
		this.http = RestClient.builder().requestFactory(factory).build();
		this.baseUrl = baseUrl;
		this.apiKey = apiKey.trim();
		this.countryCodes = countryCodes == null ? "" : countryCodes.trim();
	}

	@Override
	public List<GeoLocation> search(String query, int limit) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl).queryParam("address", query)
				.queryParam("key", apiKey).queryParam("language", "en");
		if (!countryCodes.isEmpty()) {
			// Google wants ISO codes in upper case and one filter per country, separated by '|'.
			uri.queryParam("components", "country:" + countryCodes.toUpperCase().replace(",", "|country:"));
		}
		URI target = uri.build().encode().toUri();
		String body;
		try {
			body = http.get().uri(target).retrieve().body(String.class);
		} catch (RestClientException e) {
			// The message can contain the request URL, which has the key in it: log the class only.
			log.warn("Google geocoding request failed: {}", e.getClass().getSimpleName());
			throw new ServiceUnavailableException("Location search is temporarily unavailable. Please try again.");
		}
		return parse(body, limit);
	}

	List<GeoLocation> parse(String body, int limit) {
		JsonNode root;
		try {
			root = json.readTree(body == null ? "{}" : body);
		} catch (RuntimeException e) {
			throw new ServiceUnavailableException("Location search returned an unreadable answer");
		}
		String status = root.path("status").asString("");
		if ("ZERO_RESULTS".equals(status)) {
			return List.of();
		}
		if (!"OK".equals(status)) {
			// REQUEST_DENIED, OVER_QUERY_LIMIT, INVALID_REQUEST...: a configuration or quota problem, not a bad search.
			log.warn("Google geocoding answered {}: {}", status, root.path("error_message").asString(""));
			throw new ServiceUnavailableException("Location search is temporarily unavailable. Please try again.");
		}
		List<GeoLocation> out = new ArrayList<>();
		for (JsonNode r : root.path("results")) {
			if (out.size() >= limit) {
				break;
			}
			JsonNode loc = r.path("geometry").path("location");
			double lat = loc.path("lat").asDouble(Double.NaN);
			double lng = loc.path("lng").asDouble(Double.NaN);
			if (Double.isNaN(lat) || Double.isNaN(lng)) {
				continue;
			}
			String locality = component(r, "sublocality_level_1", "sublocality");
			String sub = component(r, "sublocality_level_2", "neighborhood");
			String city = component(r, "locality", "administrative_area_level_2");
			if (locality == null) {
				locality = sub;
				sub = null;
			}
			out.add(new GeoLocation(r.path("formatted_address").asString(""), lat, lng, locality, sub, city,
					component(r, "administrative_area_level_1"), component(r, "postal_code"), component(r, "country")));
		}
		return out;
	}

	private static String component(JsonNode result, String... types) {
		for (String wanted : types) {
			for (JsonNode c : result.path("address_components")) {
				for (JsonNode t : c.path("types")) {
					if (wanted.equals(t.asString(""))) {
						String v = c.path("long_name").asString("");
						if (!v.isBlank()) {
							return v.trim();
						}
					}
				}
			}
		}
		return null;
	}
}
