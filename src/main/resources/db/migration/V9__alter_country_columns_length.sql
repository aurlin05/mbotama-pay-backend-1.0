-- Alter columns to fit enum names
ALTER TABLE gateway_routes ALTER COLUMN source_country VARCHAR(30);
ALTER TABLE gateway_routes ALTER COLUMN dest_country VARCHAR(30);
ALTER TABLE gateway_stocks ALTER COLUMN country VARCHAR(30);
