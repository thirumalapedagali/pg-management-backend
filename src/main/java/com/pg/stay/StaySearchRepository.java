package com.pg.stay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pg.stay.StayDtos.StayFilter;

/**
 * The stay search queries. Everything is parameterised (the only text spliced into SQL is fixed fragments chosen in
 * this class, never user input). Properties are filtered and sorted in the database and paged with LIMIT/OFFSET, so
 * nothing is loaded into memory before distance filtering, and one property never appears twice: beds are aggregated
 * per property before the join.
 *
 * <p>A bed is bookable now when {@code pg_beds.status = 'VACANT'}. Booking requests are enquiries and do not hold a
 * bed (approving one creates a resident, which marks the bed OCCUPIED). A resident on an approved notice frees the
 * bed only on the leaving date, so that bed is not "available now" but is reported as freeing up on that date.
 */
@Repository
public class StaySearchRepository {

	/** What a bed costs: its own override, else its section's rent, else the room's. Same rule as the public PG API. */
	private static final String BED_RENT =
			"COALESCE(b.custom_rent_override, CASE WHEN s.rent_per_bed > 0 THEN s.rent_per_bed END, r.rent)";
	/** Beds whose resident has an owner-approved notice: the date they free up (see PublicPgController). */
	private static final String BED_JOIN = "FROM pg_beds b "
			+ "JOIN room_sections s ON s.id = b.section_id AND s.pg_id = b.pg_id "
			+ "JOIN rooms r ON r.id = b.room_id AND r.pg_id = b.pg_id "
			+ "LEFT JOIN (SELECT bed_id, MIN(vacate_on) AS vacate_on FROM residents WHERE status = 'ACTIVE' "
			+ "AND notice_status = 'APPROVED' AND vacate_on IS NOT NULL AND bed_id IS NOT NULL GROUP BY bed_id) lv "
			+ "ON lv.bed_id = b.id ";

	public record Hit(long pgId, double distanceMeters, BigDecimal minRent) {
	}

	public record Found(List<Hit> hits, long total) {
	}

	/** Beds of one property that matched, grouped by sharing type. */
	public record SharingRow(long pgId, String sharingType, BigDecimal minRent, BigDecimal maxRent, int available,
			int upcoming, LocalDate availableFrom, BigDecimal minDeposit, BigDecimal maxDeposit) {
	}

	private final NamedParameterJdbcTemplate jdbc;
	private final SpatialEngine spatial;

	public StaySearchRepository(NamedParameterJdbcTemplate jdbc, SpatialEngine spatial) {
		this.jdbc = jdbc;
		this.spatial = spatial;
	}

	/** One page of matching properties, nearest / cheapest first, plus the total count. */
	public Found search(StayFilter f, LocalDateTime now) {
		spatial.requireReady();
		MapSqlParameterSource p = params(f, now);
		String with = "WITH near AS (SELECT p.id AS id, " + spatial.distanceMeters() + " AS dist_m FROM pgs p WHERE "
				+ propertyWhere(f, p) + "), fit AS (SELECT b.pg_id AS pg_id, MIN(" + BED_RENT + ") AS min_rent, MAX("
				+ BED_RENT + ") AS max_rent " + BED_JOIN + "WHERE b.pg_id IN (SELECT id FROM near)" + bedWhere(f, p)
				+ " GROUP BY b.pg_id) ";
		long total = jdbc.queryForObject(with + "SELECT COUNT(*) FROM near JOIN fit ON fit.pg_id = near.id", p,
				Long.class);
		if (total == 0 || (long) f.page() * f.size() >= total) {
			return new Found(List.of(), total);
		}
		String order = switch (f.sortBy()) {
			case NEAREST -> "near.dist_m, near.id";
			case RENT_ASC -> "fit.min_rent, near.dist_m, near.id";
			case RENT_DESC -> "fit.max_rent DESC, near.dist_m, near.id";
		};
		p.addValue("limit", f.size());
		p.addValue("offset", f.page() * f.size());
		List<Hit> hits = jdbc.query(with + "SELECT near.id, near.dist_m, fit.min_rent FROM near "
				+ "JOIN fit ON fit.pg_id = near.id ORDER BY " + order + " LIMIT :limit OFFSET :offset", p,
				(rs, i) -> new Hit(rs.getLong(1), rs.getDouble(2), rs.getBigDecimal(3)));
		return new Found(hits, total);
	}

	/** Matching beds of the given properties, grouped by sharing type: one query for the whole page. */
	public List<SharingRow> sharingRows(StayFilter f, List<Long> pgIds) {
		if (pgIds.isEmpty()) {
			return List.of();
		}
		MapSqlParameterSource p = new MapSqlParameterSource();
		addBedParams(f, p);
		p.addValue("ids", pgIds);
		String sql = "SELECT b.pg_id, s.sharing_type, MIN(" + BED_RENT + "), MAX(" + BED_RENT + "), "
				+ "SUM(CASE WHEN b.status = 'VACANT' THEN 1 ELSE 0 END), "
				+ "SUM(CASE WHEN b.status <> 'VACANT' AND lv.vacate_on IS NOT NULL THEN 1 ELSE 0 END), "
				+ "MIN(CASE WHEN b.status <> 'VACANT' THEN lv.vacate_on END), "
				+ "MIN(s.deposit_per_bed), MAX(s.deposit_per_bed) " + BED_JOIN
				+ "WHERE b.pg_id IN (:ids)" + bedWhere(f, p) + " GROUP BY b.pg_id, s.sharing_type";
		return jdbc.query(sql, p, (rs, i) -> new SharingRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
				rs.getBigDecimal(4), rs.getInt(5), rs.getInt(6),
				rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate(),
				rs.getBigDecimal(8), rs.getBigDecimal(9)));
	}

	// ---- SQL fragments ----

	private MapSqlParameterSource params(StayFilter f, LocalDateTime now) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("lat", f.latitude());
		p.addValue("lon", f.longitude());
		p.addValue("radiusM", f.radiusKm() * 1000.0);
		p.addValue("now", now);
		if (!spatial.isPostgis()) {
			// Cheap bounding box so the haversine fallback does not evaluate trig on every row.
			double dLat = Math.toDegrees(f.radiusKm() / 6371.0088);
			p.addValue("minLat", f.latitude() - dLat);
			p.addValue("maxLat", f.latitude() + dLat);
		}
		addBedParams(f, p);
		return p;
	}

	private void addBedParams(StayFilter f, MapSqlParameterSource p) {
		if (f.minRent() != null) {
			p.addValue("minRent", f.minRent());
		}
		if (f.maxRent() != null) {
			p.addValue("maxRent", f.maxRent());
		}
		if (f.sharingType() != null) {
			p.addValue("sharing", f.sharingType().name());
		}
		if (f.acType() != null) {
			p.addValue("ac", f.acType().name());
		}
	}

	/** Visibility (active + valid licence), radius, stay type, gender and amenities: all decided per property. */
	private String propertyWhere(StayFilter f, MapSqlParameterSource p) {
		StringBuilder w = new StringBuilder("p.active = TRUE AND p.license_expiry > :now AND ")
				.append(spatial.withinRadius());
		if (f.stayType() != null) {
			w.append(" AND COALESCE(UPPER(p.stay_type), 'PG') = :stayType");
			p.addValue("stayType", f.stayType());
		}
		if (f.gender() != null) {
			String allowed = switch (f.gender()) {
				case "MALE" -> "'GENTS','CO_LIVING','MALE'";
				case "FEMALE" -> "'LADIES','CO_LIVING','FEMALE'";
				default -> "'CO_LIVING','UNISEX'";
			};
			w.append(" AND (p.gender_type IS NULL OR UPPER(p.gender_type) IN (").append(allowed).append("))");
		}
		for (int i = 0; i < f.amenities().size(); i++) {
			w.append(" AND LOWER(p.amenities) LIKE :am").append(i);
			p.addValue("am" + i, "%" + f.amenities().get(i).toLowerCase() + "%");
		}
		return w.toString();
	}

	/** Which beds count: a bed must satisfy every bed-level filter, so a match means a real, bookable bed. */
	private String bedWhere(StayFilter f, MapSqlParameterSource p) {
		List<String> c = new ArrayList<>();
		// "Available" is a vacant bed now, or one whose resident has an approved notice and leaves soon.
		c.add(f.availableOnly() ? "(b.status = 'VACANT' OR lv.vacate_on IS NOT NULL)" : "b.status <> 'MAINTENANCE'");
		if (f.minRent() != null) {
			c.add(BED_RENT + " >= :minRent");
		}
		if (f.maxRent() != null) {
			c.add(BED_RENT + " <= :maxRent");
		}
		if (f.sharingType() != null) {
			c.add("s.sharing_type = :sharing");
		}
		if (f.acType() != null) {
			c.add("COALESCE(s.ac_type, r.ac_type) = :ac");
		}
		if (f.attachedBathroom() != null) {
			c.add(f.attachedBathroom() ? "(s.attached_bathroom = TRUE OR r.attached_bathroom = TRUE)"
					: "(s.attached_bathroom = FALSE AND r.attached_bathroom = FALSE)");
		}
		if (f.gender() != null) {
			c.add(switch (f.gender()) {
				case "MALE" -> "r.gender_policy IN ('MALE','UNISEX')";
				case "FEMALE" -> "r.gender_policy IN ('FEMALE','UNISEX')";
				default -> "r.gender_policy IN ('UNISEX','COUPLE')";
			});
		}
		return " AND " + String.join(" AND ", c);
	}
}
