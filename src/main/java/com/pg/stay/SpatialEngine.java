package com.pg.stay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.pg.common.ServiceUnavailableException;

/**
 * Distance maths for stay search, in one place.
 *
 * <ul>
 * <li><b>postgis</b> (production): {@code pgs.location geography(Point,4326)} is a <i>generated column</i> computed from
 * {@code latitude}/{@code longitude}, so it is correct on every insert and update with no application code.
 * Radius filtering uses {@code ST_DWithin} (GiST index) and distance uses {@code ST_Distance}, both in metres on the
 * WGS84 spheroid.</li>
 * <li><b>haversine</b>: exact great-circle SQL for databases without PostGIS (H2, used by the dev/test profile only).
 * It is never chosen automatically: if PostGIS is requested but missing, search answers 503 with install steps rather
 * than quietly switching to a less accurate formula.</li>
 * </ul>
 */
@Component
public class SpatialEngine implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SpatialEngine.class);
	static final String INSTALL_HINT = "PostGIS is not available in this PostgreSQL database. Install the extension "
			+ "(Debian/Ubuntu: apt install postgresql-<version>-postgis-3; Docker: use the postgis/postgis image; "
			+ "Neon / RDS / Cloud SQL / Azure: enable the 'postgis' extension for the database) and restart, or run "
			+ "'CREATE EXTENSION postgis;' as a database owner. See db/migration/V4__stay_location_search.sql.";

	private final boolean postgis;
	private final JdbcTemplate jdbc;
	private volatile String unavailableReason;

	public SpatialEngine(JdbcTemplate jdbc, @Value("${app.stays.spatial-engine:postgis}") String engine) {
		this.jdbc = jdbc;
		String e = engine.trim().toLowerCase();
		if (!e.equals("postgis") && !e.equals("haversine")) {
			throw new IllegalStateException("app.stays.spatial-engine must be postgis or haversine, not '" + engine + "'");
		}
		this.postgis = e.equals("postgis");
	}

	public boolean isPostgis() {
		return postgis;
	}

	/** Creates the PostGIS extension, the generated geography column and its GiST index (all idempotent). */
	@Override
	public void run(ApplicationArguments args) {
		if (!postgis) {
			log.warn("Stay search is using the haversine fallback (app.stays.spatial-engine=haversine). "
					+ "Use PostGIS in production.");
			return;
		}
		try {
			jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
			jdbc.execute("ALTER TABLE pgs ADD COLUMN IF NOT EXISTS location geography(Point,4326) "
					+ "GENERATED ALWAYS AS (CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL "
					+ "THEN CAST(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) AS geography) END) STORED");
			jdbc.execute("CREATE INDEX IF NOT EXISTS idx_pgs_location ON pgs USING GIST (location)");
			jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pgs_lat_lon') THEN "
					+ "ALTER TABLE pgs ADD CONSTRAINT ck_pgs_lat_lon CHECK ((latitude IS NULL) = (longitude IS NULL) "
					+ "AND (latitude IS NULL OR latitude BETWEEN -90 AND 90) "
					+ "AND (longitude IS NULL OR longitude BETWEEN -180 AND 180)); END IF; END $$");
			unavailableReason = null;
			log.info("PostGIS ready: pgs.location (geography) + GiST index idx_pgs_location");
		} catch (RuntimeException e) {
			unavailableReason = INSTALL_HINT;
			log.error("{} Cause: {}", INSTALL_HINT, rootMessage(e));
		}
	}

	/** Called before every search. */
	public void requireReady() {
		if (unavailableReason != null) {
			throw new ServiceUnavailableException(unavailableReason);
		}
	}

	/** SQL for the distance in metres from property alias {@code p} to the point (:lat, :lon). */
	public String distanceMeters() {
		if (postgis) {
			return "ST_Distance(p.location, " + point() + ")";
		}
		return "(2 * 6371008.8 * ASIN(LEAST(1, SQRT(POWER(SIN(RADIANS(p.latitude - :lat) / 2), 2) "
				+ "+ COS(RADIANS(:lat)) * COS(RADIANS(p.latitude)) * POWER(SIN(RADIANS(p.longitude - :lon) / 2), 2)))))";
	}

	/** SQL predicate "within :radiusM metres of (:lat, :lon)"; uses the GiST index on PostGIS. */
	public String withinRadius() {
		if (postgis) {
			return "p.location IS NOT NULL AND ST_DWithin(p.location, " + point() + ", :radiusM)";
		}
		return "p.latitude BETWEEN :minLat AND :maxLat AND " + distanceMeters() + " <= :radiusM";
	}

	private static String point() {
		return "CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)";
	}

	private static String rootMessage(Throwable t) {
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return t.getMessage();
	}
}
