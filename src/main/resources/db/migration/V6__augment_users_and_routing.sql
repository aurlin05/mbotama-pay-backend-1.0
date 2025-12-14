-- V6__augment_users_and_routing.sql
-- Add missing user columns to align with JPA entity and avoid Hibernate auto DDL conflicts

ALTER TABLE users ADD COLUMN IF NOT EXISTS address VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS city VARCHAR(120);
ALTER TABLE users ADD COLUMN IF NOT EXISTS date_of_birth DATE;

-- No route inserts here; routes and stocks are initialized programmatically by RoutingAutoConfigurator
