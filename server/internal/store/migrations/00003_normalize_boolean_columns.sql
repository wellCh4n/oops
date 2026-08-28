-- +goose Up
-- Databases whose tables predate Flyway were created by Hibernate ddl-auto,
-- which typed Boolean columns as BIT(1); the Go MySQL driver cannot scan
-- BIT(1) into bool. Normalize to TINYINT(1), the type every Flyway/goose-built
-- schema already uses (a no-op there). Values are preserved by MySQL's cast.

ALTER TABLE domain MODIFY https tinyint(1) DEFAULT NULL;
ALTER TABLE user MODIFY enabled tinyint(1) NOT NULL DEFAULT 1;
