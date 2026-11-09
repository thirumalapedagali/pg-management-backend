package com.pg.dashboard;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Short-lived cache for the read-heavy dashboard numbers. Every write request evicts the affected property
 * ({@link CacheEvictionFilter}), so the short TTL only covers changes made outside the API.
 */
@Component
public class DashboardCache {

	private record Key(String kind, Long pgId, LocalDate day) {
	}

	/** Bounded, so a 10-property owner cannot exhaust the DB connection pool. */
	private final ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
		Thread t = new Thread(r, "dashboard-worker");
		t.setDaemon(true);
		return t;
	});

	private final Cache<Key, Object> pgData = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60))
			.maximumSize(2000).build();
	private final Cache<String, Object> owned = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30))
			.maximumSize(500).build();

	private record RoomsKey(Long pgId, boolean staff) {
	}

	/**
	 * Room / bed layout and occupancy. It only changes when a room or a resident is saved, and those writes evict it
	 * ({@link #evictRooms}); the long expiry is just a safety net.
	 */
	private final Cache<RoomsKey, Object> rooms = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1))
			.maximumSize(1000).build();

	/** Per-property value of the given kind for a day; the loader runs only on a miss (failures are not cached). */
	@SuppressWarnings("unchecked")
	public <T> T pg(String kind, Long pgId, LocalDate day, Supplier<T> loader) {
		return (T) pgData.get(new Key(kind, pgId, day), k -> loader.get());
	}

	/** A property's room list; staff and residents see different data, so they are cached separately. */
	@SuppressWarnings("unchecked")
	public <T> T rooms(Long pgId, boolean staff, Supplier<T> loader) {
		return (T) rooms.get(new RoomsKey(pgId, staff), k -> loader.get());
	}

	/** An owner's property list, keyed by username. */
	@SuppressWarnings("unchecked")
	public <T> T owned(String username, Supplier<T> loader) {
		return (T) owned.get(username, k -> loader.get());
	}

	/**
	 * Maps every item concurrently (the work is dominated by network round-trips to the database). Workers do not
	 * inherit the request's tenant context, so {@code fn} must take everything it needs from its argument.
	 */
	public <T, R> List<R> parallelMap(List<T> items, Function<T, R> fn) {
		if (items.size() <= 1) {
			return items.stream().map(fn).toList();
		}
		List<CompletableFuture<R>> futures = items.stream()
				.map(i -> CompletableFuture.supplyAsync(() -> fn.apply(i), pool)).toList();
		try {
			return futures.stream().map(CompletableFuture::join).toList();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException re) {
				throw re;
			}
			throw e;
		}
	}

	public void evictPg(Long pgId) {
		pgData.asMap().keySet().removeIf(k -> k.pgId().equals(pgId));
	}

	public void evictRooms(Long pgId) {
		rooms.asMap().keySet().removeIf(k -> k.pgId().equals(pgId));
	}

	public void evictOwned() {
		owned.invalidateAll();
	}

	public void evictAll() {
		pgData.invalidateAll();
		rooms.invalidateAll();
		owned.invalidateAll();
	}
}
