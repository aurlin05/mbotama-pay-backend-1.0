-- Alter columns to fit enum names
ALTER TABLE gateway_routes ALTER COLUMN source_country TYPE VARCHAR(30);
ALTER TABLE gateway_routes ALTER COLUMN dest_country TYPE VARCHAR(30);
ALTER TABLE gateway_stocks ALTER COLUMN country TYPE VARCHAR(30);
