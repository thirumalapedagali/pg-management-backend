package com.pg.location;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pg.common.BadRequestException;

/**
 * The only door to the geocoding provider. Answers are cached by normalised query text (places do not move), so
 * repeated searches for "Marathahalli" never reach the provider; failures are not cached. A search that found nothing
 * is remembered briefly so a typo hammered by a client does not become a stream of upstream calls.
 */
@Service
public class GeocodingService {

	static final int MAX_SUGGESTIONS = 5;
	static final int MIN_QUERY = 3;
	static final int MAX_QUERY = 120;

	private final GeocodingProvider provider;
	private final Cache<String, List<GeoLocation>> found;
	private final Cache<String, Boolean> notFound = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10))
			.maximumSize(2000).build();

	public GeocodingService(GeocodingProvider provider,
			@Value("${app.geocoding.cache-hours:168}") long cacheHours,
			@Value("${app.geocoding.cache-size:5000}") long cacheSize) {
		this.provider = provider;
		this.found = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(cacheHours)).maximumSize(cacheSize).build();
	}

	/** Up to five matches, best first. Throws {@link BadRequestException} for an unusable query. */
	public List<GeoLocation> suggest(String query) {
		String key = normalise(query);
		List<GeoLocation> hit = found.getIfPresent(key);
		if (hit != null) {
			return hit;
		}
		if (notFound.getIfPresent(key) != null) {
			return List.of();
		}
		List<GeoLocation> fresh = List.copyOf(provider.search(key, MAX_SUGGESTIONS));
		if (fresh.isEmpty()) {
			notFound.put(key, Boolean.TRUE);
		} else {
			found.put(key, fresh);
		}
		return fresh;
	}

	/** The single best match, or null when the place is unknown. */
	public GeoLocation resolve(String query) {
		List<GeoLocation> all = suggest(query);
		return all.isEmpty() ? null : all.get(0);
	}

	static String normalise(String query) {
		String q = query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
		if (q.length() < MIN_QUERY) {
			throw new BadRequestException("Type at least " + MIN_QUERY + " characters to search a place");
		}
		if (q.length() > MAX_QUERY) {
			throw new BadRequestException("The place name is too long");
		}
		return q;
	}
}
