-- Pictures of properties, rooms and residents (several per owner; a resident has one PHOTO and one ID_CARD).
--   owner_type = PROPERTY | ROOM | RESIDENT, owner_id = id of that row, pg_id = the property it belongs to
-- With ddl-auto=update Hibernate creates this table itself; this script is for databases managed by hand.
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS media_files (
    id          BIGSERIAL PRIMARY KEY,
    pg_id       BIGINT       NOT NULL,
    owner_type  VARCHAR(20)  NOT NULL,
    owner_id    BIGINT       NOT NULL,
    kind        VARCHAR(20)  NOT NULL,
    public_id   VARCHAR(255) NOT NULL,
    url         VARCHAR(1000) NOT NULL,
    created_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_media_owner ON media_files (pg_id, owner_type, owner_id);
