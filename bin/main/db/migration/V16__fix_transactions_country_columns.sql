-- Fix transactions table country columns length to fit enum names like BURKINA_FASO, CONGO_BRAZZAVILLE
ALTER TABLE transactions ALTER COLUMN source_country TYPE VARCHAR(20);
ALTER TABLE transactions ALTER COLUMN dest_country TYPE VARCHAR(20);
