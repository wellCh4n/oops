CREATE INDEX `idx_pipeline_status`
    ON `pipeline` (`status`);

CREATE INDEX `idx_pipeline_app_created`
    ON `pipeline` (`namespace`, `application_name`, `created_time`);

CREATE INDEX `idx_pipeline_app_env_created`
    ON `pipeline` (`namespace`, `application_name`, `environment`, `created_time`);

CREATE INDEX `idx_pipeline_app_status_created`
    ON `pipeline` (`namespace`, `application_name`, `status`, `created_time`);

CREATE INDEX `idx_application_build_config_app`
    ON `application_build_config` (`namespace`, `application_name`);

CREATE INDEX `idx_application_runtime_spec_app`
    ON `application_runtime_spec` (`namespace`, `application_name`);

CREATE INDEX `idx_application_environment_app`
    ON `application_environment` (`namespace`, `application_name`);

CREATE INDEX `idx_application_service_config_app`
    ON `application_service_config` (`namespace`, `application_name`);

CREATE INDEX `idx_user_username`
    ON `user` (`username`);

CREATE INDEX `idx_user_email`
    ON `user` (`email`);

CREATE INDEX `idx_external_account_provider_subject`
    ON `external_account` (`provider`, `provider_user_id`);

CREATE INDEX `idx_external_account_provider_user`
    ON `external_account` (`provider`, `user_id`);
