-- Collapse pipeline.branch + pipeline.publish_repository into a single JSON publish_config column.
-- GIT  -> {"type":"GIT","repository":...,"branch":...}
-- ZIP  -> {"type":"ZIP","url":...} when the legacy value looks like a URL, otherwise {"type":"ZIP","objectKey":...}
ALTER TABLE `pipeline` ADD COLUMN `publish_config` text DEFAULT NULL;

UPDATE `pipeline`
SET `publish_config` = CASE
    WHEN `publish_type` = 'ZIP' AND (`publish_repository` LIKE 'http://%' OR `publish_repository` LIKE 'https://%')
        THEN JSON_OBJECT('type', 'ZIP', 'url', `publish_repository`)
    WHEN `publish_type` = 'ZIP'
        THEN JSON_OBJECT('type', 'ZIP', 'objectKey', `publish_repository`)
    ELSE JSON_OBJECT('type', 'GIT', 'repository', `publish_repository`, 'branch', `branch`)
END
WHERE `publish_repository` IS NOT NULL OR `branch` IS NOT NULL;

ALTER TABLE `pipeline` DROP COLUMN `branch`, DROP COLUMN `publish_repository`;
