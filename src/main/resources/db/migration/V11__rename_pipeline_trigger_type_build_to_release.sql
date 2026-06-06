UPDATE `pipeline`
SET `trigger_type` = 'RELEASE'
WHERE `trigger_type` = 'BUILD';

ALTER TABLE `pipeline`
    ALTER COLUMN `trigger_type` SET DEFAULT 'RELEASE';
