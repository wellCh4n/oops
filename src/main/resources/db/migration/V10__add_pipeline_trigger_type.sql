ALTER TABLE `pipeline`
    ADD COLUMN `trigger_type` varchar(32) DEFAULT 'BUILD',
    ADD COLUMN `rollback_from_pipeline_id` varchar(255) DEFAULT NULL;
