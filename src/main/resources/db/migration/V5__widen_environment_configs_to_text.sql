ALTER TABLE `application_build_config` MODIFY COLUMN `environment_configs` TEXT;
ALTER TABLE `application_service_config` MODIFY COLUMN `environment_configs` TEXT;
ALTER TABLE `application_runtime_spec` MODIFY COLUMN `environment_configs` TEXT;
