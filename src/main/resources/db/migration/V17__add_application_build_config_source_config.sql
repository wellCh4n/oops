-- Collapse application_build_config.repository into a source-type-aware source_config JSON column.
-- The repository field is GIT-only; ZIP never used it. The type discriminator mirrors source_type.
-- GIT (or legacy NULL source_type) -> {"type":"GIT","repository":...}
-- ZIP                              -> {"type":"ZIP"}
ALTER TABLE `application_build_config` ADD COLUMN `source_config` text DEFAULT NULL;

UPDATE `application_build_config`
SET `source_config` = CASE
    WHEN `source_type` = 'ZIP' THEN JSON_OBJECT('type', 'ZIP')
    ELSE JSON_OBJECT('type', 'GIT', 'repository', `repository`)
END;

ALTER TABLE `application_build_config` DROP COLUMN `repository`;
