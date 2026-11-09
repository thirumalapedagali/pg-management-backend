-- Attachments: every tenant-owned table (entities extending com.pg.tenant.BaseEntity) can carry one file.
--   attachment_id  = Cloudinary public id (needed to delete the file later)
--   attachment_url = HTTPS link to the file
-- With ddl-auto=update Hibernate adds these columns itself; this script is for databases managed by hand.
-- Safe to run more than once.

ALTER TABLE announcements          ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE announcements          ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE booking_requests       ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE booking_requests       ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE complaints             ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE complaints             ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE expense_categories     ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE expense_categories     ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE expenses               ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE expenses               ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE payments               ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE payments               ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE pg_beds                ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE pg_beds                ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE property_amenities     ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE property_amenities     ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE property_field_values  ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE property_field_values  ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE resident_rent_changes  ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE resident_rent_changes  ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE residents              ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE residents              ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE room_amenities         ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE room_amenities         ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE room_sections          ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE room_sections          ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE room_sharing_options   ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE room_sharing_options   ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE rooms                  ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE rooms                  ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
ALTER TABLE support_contacts       ADD COLUMN IF NOT EXISTS attachment_id VARCHAR(255);
ALTER TABLE support_contacts       ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(1000);
