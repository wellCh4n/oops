-- +goose Up
-- Baseline schema for the Go backend: the full OOPS schema as of the
-- Java side's Flyway V21, dumped from a live database. IF NOT EXISTS makes
-- the baseline a no-op on databases that Flyway already built, so goose
-- simply records version 1 there; fresh databases are created from scratch.
-- Later changes are append-only goose migrations.

CREATE TABLE IF NOT EXISTS `application` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `icon` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_application_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_alert_state` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `environment_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `metric` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `firing` bit(1) NOT NULL DEFAULT b'0',
  `firing_since` datetime(6) DEFAULT NULL,
  `last_notified_time` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_state_target` (`namespace`(64),`application_name`(64),`environment_name`(64),`metric`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_build_config` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `build_image` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `environment_configs` text COLLATE utf8mb4_unicode_ci,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `docker_file_config` text COLLATE utf8mb4_unicode_ci,
  `source_config` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_application_build_config_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_collaborator` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_application_collaborator` (`namespace`,`application_name`,`user_id`),
  KEY `idx_application_collaborator_app` (`namespace`,`application_name`),
  KEY `idx_application_collaborator_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_environment` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `environment_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_application_environment_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_expert_config` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `environment_configs` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_runtime_spec` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `environment_configs` text COLLATE utf8mb4_unicode_ci,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `health_check` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_application_runtime_spec_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_service_config` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `environment_configs` text COLLATE utf8mb4_unicode_ci,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `port` int DEFAULT NULL,
  `internal_ports` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_application_service_config_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `domain` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `cert_mode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cert_not_after` datetime(6) DEFAULT NULL,
  `cert_pem` text COLLATE utf8mb4_unicode_ci,
  `cert_subject` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `host` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `https` tinyint(1) DEFAULT NULL,
  `key_pem` text COLLATE utf8mb4_unicode_ci,
  `environment_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain_host` (`host`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `environment` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `build_storage_class` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image_repository_password` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image_repository_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image_repository_username` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `api_server_token` text COLLATE utf8mb4_unicode_ci,
  `api_server_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `work_namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `git_credential` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_environment_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `external_account` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `provider` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `provider_user_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_external_account_provider_subject` (`provider`,`provider_user_id`),
  KEY `idx_external_account_provider_user` (`provider`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `namespace` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `pipeline` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `application_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `artifact` text COLLATE utf8mb4_unicode_ci,
  `environment` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `publish_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `deploy_mode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `operator_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `message` text COLLATE utf8mb4_unicode_ci,
  `trigger_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT 'RELEASE',
  `rollback_from_pipeline_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `publish_config` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_pipeline_status` (`status`),
  KEY `idx_pipeline_app_created` (`namespace`,`application_name`,`created_time`),
  KEY `idx_pipeline_app_env_created` (`namespace`,`application_name`,`environment`,`created_time`),
  KEY `idx_pipeline_app_status_created` (`namespace`,`application_name`,`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `user` (
  `id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `role` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `username` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `access_token` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_access_token` (`access_token`),
  KEY `idx_user_username` (`username`),
  KEY `idx_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
