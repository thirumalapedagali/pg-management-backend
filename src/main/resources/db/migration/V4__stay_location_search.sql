-- Location-based stay search (PostGIS). Safe to run more than once.
-- With the default settings the application runs the equivalent statements itself at startup (SpatialEngine), so this
-- script is for databases managed by hand. The user running it needs rights to CREATE EXTENSION (on Neon, RDS,
-- Cloud SQL and Azure the extension is enabled per database from the provider's console or with this statement).

CREATE EXTENSION IF NOT EXISTS postgis;

-- Where the property is. The app stores latitude/longitude (WGS84 degrees) and the descriptive parts.
ALTER TABLE pgs ADD COLUMN IF NOT EXISTS latitude    DOUBLE PRECISION;
ALTER TABLE pgs ADD COLUMN IF NOT EXISTS longitude   DOUBLE PRECISION;
ALTER TABLE pgs ADD COLUMN IF NOT EXISTS locality    VARCHAR(120);
ALTER TABLE pgs ADD COLUMN IF NOT EXISTS sub_locality VARCHAR(120);
ALTER TABLE pgs ADD COLUMN IF NOT EXISTS pincode     VARCHAR(12);
ALTER TABLE pgs ADD COLUMN IF NOT EXISTS country     VARCHAR(80);
-- (city and state already exist)

-- geography(Point,4326), computed by the database from latitude/longitude, so it can never be out of step with them:
-- ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography   (note: longitude first)
ALTER TABLE pgs ADD COLUMN IF NOT EXISTS location geography(Point,4326)
    GENERATED ALWAYS AS (
        CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL
             THEN CAST(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) AS geography) END
    ) STORED;

-- Radius searches (ST_DWithin) use this index.
CREATE INDEX IF NOT EXISTS idx_pgs_location ON pgs USING GIST (location);

-- Coordinates come as a pair and inside the valid range.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pgs_lat_lon') THEN
        ALTER TABLE pgs ADD CONSTRAINT ck_pgs_lat_lon CHECK (
            (latitude IS NULL) = (longitude IS NULL)
            AND (latitude  IS NULL OR latitude  BETWEEN -90  AND 90)
            AND (longitude IS NULL OR longitude BETWEEN -180 AND 180));
    END IF;
END $$;

-- Search reads the beds of nearby properties; these exist already from the entities, listed for reference:
--   pg_beds (pg_id, section_id), pg_beds (pg_id, room_id)
-- Existing properties have no coordinates until the owner sets them (or an admin does); until then they are simply
-- not returned by location search. Nothing is guessed or back-filled.
