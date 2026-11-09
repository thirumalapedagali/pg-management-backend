package com.pg.stay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.pg.attachment.MediaFile;
import com.pg.attachment.MediaFileRepository;
import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.common.Paged;
import com.pg.location.GeoLocation;
import com.pg.location.GeocodingService;
import com.pg.pg.Pg;
import com.pg.pg.PgLocation;
import com.pg.pg.PgRepository;
import com.pg.publicapi.PublicPgController;
import com.pg.publicapi.PublicPgController.PublicPgDetailResponse;
import com.pg.room.AcType;
import com.pg.room.SharingType;
import com.pg.stay.StayDtos.SharingOption;
import com.pg.stay.StayDtos.SortBy;
import com.pg.stay.StayDtos.StayCard;
import com.pg.stay.StayDtos.StayFilter;
import com.pg.stay.StayDtos.StaySearchResult;
import com.pg.stay.StaySearchRepository.Found;
import com.pg.stay.StaySearchRepository.Hit;
import com.pg.stay.StaySearchRepository.SharingRow;

@Service
public class StayService {

	/** Property detail for residents: the public detail without the owner's payment (UPI) data, plus search facts. */
	public record StayDetail(PublicPgDetailResponse property, String locality, String subLocality, String pincode,
			Double latitude, Double longitude, int availableBeds, BigDecimal startingRent,
			List<SharingOption> sharingOptions) {
	}

	/** Raw request values; {@link #filter} validates them. */
	public record Query(Double latitude, Double longitude, Double radiusKm, BigDecimal minRent, BigDecimal maxRent,
			String sharingType, String stayType, String gender, Boolean availableOnly, String acType,
			Boolean attachedBathroom, String amenities, String sortBy, Integer page, Integer size) {
	}

	private final StaySearchRepository repo;
	private final PgRepository pgs;
	private final MediaFileRepository media;
	private final GeocodingService geocoding;
	private final PublicPgController publicApi;
	private final double maxRadiusKm;
	private final double defaultRadiusKm;
	private final int maxPageSize;

	public StayService(StaySearchRepository repo, PgRepository pgs, MediaFileRepository media,
			GeocodingService geocoding, PublicPgController publicApi,
			@Value("${app.stays.max-radius-km:50}") double maxRadiusKm,
			@Value("${app.stays.default-radius-km:5}") double defaultRadiusKm,
			@Value("${app.stays.max-page-size:50}") int maxPageSize) {
		this.repo = repo;
		this.pgs = pgs;
		this.media = media;
		this.geocoding = geocoding;
		this.publicApi = publicApi;
		this.maxRadiusKm = maxRadiusKm;
		this.defaultRadiusKm = defaultRadiusKm;
		this.maxPageSize = maxPageSize;
	}

	/** GET /api/stays/nearby: the coordinates come from the client (GPS or a picked suggestion). */
	public Paged<StayCard> nearby(Query q) {
		if (q.latitude() == null || q.longitude() == null) {
			throw new BadRequestException("latitude and longitude are required");
		}
		return run(filter(q, q.latitude(), q.longitude()));
	}

	/** GET /api/stays/search: resolve the text to a place (cached), then search around it. */
	public StaySearchResult search(String query, Query q) {
		if (query == null || query.isBlank()) {
			throw new BadRequestException("query is required");
		}
		GeoLocation place;
		if (q.latitude() != null && q.longitude() != null) {
			// The client already picked a suggestion: do not call the geocoder again for coordinates we have.
			PgLocation.validate(q.latitude(), q.longitude());
			place = new GeoLocation(query.trim(), q.latitude(), q.longitude(), null, null, null, null, null, null);
		} else {
			place = geocoding.resolve(query);
			if (place == null) {
				throw new NotFoundException("We could not find \"" + query.trim() + "\". Try a nearby area or a city name.");
			}
		}
		return new StaySearchResult(place, run(filter(q, place.latitude(), place.longitude())));
	}

	public StayDetail details(Long propertyId) {
		Pg pg = pgs.findById(propertyId).orElse(null);
		if (pg == null || !visible(pg)) {
			throw new NotFoundException("Stay not found");
		}
		PublicPgDetailResponse d = publicApi.getPgDetails(propertyId);
		PublicPgDetailResponse safe = new PublicPgDetailResponse(d.id(), d.name(), null, d.mobile(), null,
				d.address(), d.city(), d.state(), d.totalFloors(), d.stayType(), d.unitLabel(), d.locationUrl(),
				d.genderType(), null, null, d.description(), d.amenities(), d.startingPrice(), d.totalRooms(),
				d.totalBeds(), d.totalVacantBeds(), d.rooms(), d.images());
		StayFilter all = new StayFilter(0, 0, 0, null, null, null, null, null, true, null, null, List.of(),
				SortBy.NEAREST, 0, 1);
		List<SharingOption> options = options(repo.sharingRows(all, List.of(propertyId)));
		int available = options.stream().mapToInt(SharingOption::availableBeds).sum();
		BigDecimal from = options.stream().map(SharingOption::rentPerMonth).min(Comparator.naturalOrder()).orElse(null);
		return new StayDetail(safe, pg.getLocality(), pg.getSubLocality(), pg.getPincode(), pg.getLatitude(),
				pg.getLongitude(), available, from, options);
	}

	// ---- internals ----

	private Paged<StayCard> run(StayFilter f) {
		Found found = repo.search(f, LocalDateTime.now());
		if (found.hits().isEmpty()) {
			return Paged.of(new PageImpl<>(List.of(), PageRequest.of(f.page(), f.size()), found.total()));
		}
		List<Long> ids = found.hits().stream().map(h -> h.pgId()).toList();
		Map<Long, Pg> byId = new HashMap<>();
		pgs.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
		Map<Long, List<String>> pictures = new HashMap<>();
		for (MediaFile m : media.findByOwnerTypeAndOwnerIdInOrderById("PROPERTY", ids)) {
			pictures.computeIfAbsent(m.getOwnerId(), k -> new ArrayList<>()).add(m.getUrl());
		}
		Map<Long, List<SharingRow>> rows = new HashMap<>();
		for (SharingRow r : repo.sharingRows(f, ids)) {
			rows.computeIfAbsent(r.pgId(), k -> new ArrayList<>()).add(r);
		}
		List<StayCard> cards = new ArrayList<>();
		for (Hit h : found.hits()) {
			Pg pg = byId.get(h.pgId());
			if (pg == null) {
				continue;
			}
			List<SharingOption> options = options(rows.getOrDefault(h.pgId(), List.of()));
			BigDecimal max = options.stream().map(SharingOption::maxRentPerMonth).max(Comparator.naturalOrder())
					.orElse(h.minRent());
			cards.add(new StayCard(pg.getId(), pg.getName(), pg.getStayType() != null ? pg.getStayType() : "PG",
					pg.getGenderType() != null ? pg.getGenderType() : "CO_LIVING", pg.getAddress(), pg.getLocality(),
					pg.getSubLocality(), pg.getCity(), pg.getState(), pg.getPincode(), pg.getLatitude(),
					pg.getLongitude(), round2(h.distanceMeters() / 1000.0), h.minRent(), max,
					options.stream().mapToInt(SharingOption::availableBeds).sum(),
					options.stream().mapToInt(SharingOption::upcomingBeds).sum(), soonest(options), options, amenities(pg),
					pictures.getOrDefault(pg.getId(), List.of()), pg.getDescription(), pg.getMobile()));
		}
		return Paged.of(new PageImpl<>(cards, PageRequest.of(f.page(), f.size()), found.total()));
	}

	private static List<SharingOption> options(List<SharingRow> rows) {
		Map<String, SharingOption> merged = new LinkedHashMap<>();
		rows.stream().sorted(Comparator.comparing(SharingRow::minRent)).forEach(r -> {
			SharingType t = SharingType.valueOf(r.sharingType());
			merged.put(r.sharingType(), new SharingOption(t, label(t), r.minRent(), r.maxRent(), r.available(),
					r.upcoming(), r.availableFrom()));
		});
		return List.copyOf(merged.values());
	}

	private static java.time.LocalDate soonest(List<SharingOption> options) {
		return options.stream().map(SharingOption::availableFrom).filter(java.util.Objects::nonNull)
				.min(Comparator.naturalOrder()).orElse(null);
	}

	static String label(SharingType t) {
		return switch (t) {
			case PRIVATE -> "Single";
			case TWO_SHARING -> "2 Sharing";
			case THREE_SHARING -> "3 Sharing";
			case FOUR_SHARING -> "4 Sharing";
			case DORM -> "Dormitory";
			case WHOLE_ROOM -> "Entire unit";
		};
	}

	/** Only what the owner listed: no invented defaults. */
	private static List<String> amenities(Pg pg) {
		List<String> out = new ArrayList<>();
		if (pg.getAmenities() != null) {
			for (String s : pg.getAmenities().split(",")) {
				if (!s.isBlank()) {
					out.add(s.trim());
				}
			}
		}
		return out;
	}

	/** Same visibility rule the search applies in SQL: the property is switched on and its licence is current. */
	private static boolean visible(Pg pg) {
		return pg.isLicenseValid();
	}

	private StayFilter filter(Query q, double lat, double lon) {
		PgLocation.validate(lat, lon);
		double radius = q.radiusKm() == null ? defaultRadiusKm : q.radiusKm();
		if (Double.isNaN(radius) || radius <= 0) {
			throw new BadRequestException("radiusKm must be greater than 0");
		}
		if (radius > maxRadiusKm) {
			throw new BadRequestException("radiusKm cannot be more than " + (int) maxRadiusKm);
		}
		if (q.minRent() != null && q.minRent().signum() < 0 || q.maxRent() != null && q.maxRent().signum() < 0) {
			throw new BadRequestException("Rent cannot be negative");
		}
		if (q.minRent() != null && q.maxRent() != null && q.minRent().compareTo(q.maxRent()) > 0) {
			throw new BadRequestException("minRent cannot be more than maxRent");
		}
		int page = q.page() == null ? 0 : q.page();
		int size = q.size() == null ? 20 : q.size();
		if (page < 0) {
			throw new BadRequestException("page cannot be negative");
		}
		if (size < 1 || size > maxPageSize) {
			throw new BadRequestException("size must be between 1 and " + maxPageSize);
		}
		return new StayFilter(lat, lon, radius, q.minRent(), q.maxRent(), sharing(q.sharingType()),
				q.stayType() == null || q.stayType().isBlank() ? null : q.stayType().trim().toUpperCase(Locale.ROOT),
				gender(q.gender()), q.availableOnly() == null || q.availableOnly(), ac(q.acType()),
				q.attachedBathroom(), amenityList(q.amenities()), SortBy.parse(q.sortBy()), page, size);
	}

	/** Accepts the enum names and the everyday words: SINGLE, DOUBLE/TWO, TRIPLE, QUAD, 1-4. */
	static SharingType sharing(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		return switch (s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_')) {
			case "PRIVATE", "SINGLE", "1" -> SharingType.PRIVATE;
			case "TWO_SHARING", "DOUBLE", "2", "2_SHARING" -> SharingType.TWO_SHARING;
			case "THREE_SHARING", "TRIPLE", "3", "3_SHARING" -> SharingType.THREE_SHARING;
			case "FOUR_SHARING", "QUAD", "4", "4_SHARING" -> SharingType.FOUR_SHARING;
			case "DORM", "DORMITORY" -> SharingType.DORM;
			case "WHOLE_ROOM", "ENTIRE", "WHOLE" -> SharingType.WHOLE_ROOM;
			default -> throw new BadRequestException("Unknown sharingType: " + s);
		};
	}

	static String gender(String s) {
		if (s == null || s.isBlank() || s.equalsIgnoreCase("ALL") || s.equalsIgnoreCase("ANY")) {
			return null;
		}
		return switch (s.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
			case "MALE", "GENTS", "MEN", "BOYS" -> "MALE";
			case "FEMALE", "LADIES", "WOMEN", "GIRLS" -> "FEMALE";
			case "UNISEX", "CO_LIVING", "COLIVING" -> "UNISEX";
			default -> throw new BadRequestException("gender must be MALE, FEMALE or UNISEX");
		};
	}

	private static AcType ac(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return AcType.valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
		} catch (IllegalArgumentException e) {
			throw new BadRequestException("acType must be AC or NON_AC");
		}
	}

	private static List<String> amenityList(String s) {
		if (s == null || s.isBlank()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String a : s.split(",")) {
			// LIKE wildcards in user input would turn "wi%" into a pattern; they are not part of any amenity name.
			String t = a.replace("%", "").replace("_", "").trim();
			if (!t.isEmpty() && out.size() < 5) {
				out.add(t);
			}
		}
		return out;
	}

	private static double round2(double v) {
		return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
	}
}
