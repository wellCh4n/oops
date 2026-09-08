-- Databases that predate Flyway were created by Hibernate's DDL, and Hibernate 6 maps an
-- @Enumerated(STRING) field on MySQL to a native ENUM column whose value set is frozen at
-- whatever the Java enum held at the time: application_build_config.source_type and
-- pipeline.publish_type became ENUM('GIT','ZIP'), so writing the new IMAGE source fails with
-- "Data truncated for column 'source_type'" and the whole save rolls back. The V1 baseline
-- declares every one of these columns as varchar(255), which is what a fresh database gets;
-- this brings the baselined databases in line so a new enum constant never needs a migration.
-- MODIFY keeps the stored labels (an ENUM converts to its string values, not its indexes) and
-- is a no-op on a column that is already varchar(255).

ALTER TABLE `application_build_config` MODIFY COLUMN `source_type` varchar(255) DEFAULT NULL;
ALTER TABLE `pipeline` MODIFY COLUMN `publish_type` varchar(255) DEFAULT NULL;
ALTER TABLE `domain` MODIFY COLUMN `cert_mode` varchar(255) DEFAULT NULL;
ALTER TABLE `external_account` MODIFY COLUMN `provider` varchar(255) DEFAULT NULL;
ALTER TABLE `user` MODIFY COLUMN `role` varchar(255) DEFAULT NULL;
