-- The schema a fresh installation starts from, and nothing more.
--
-- It is byte-for-byte what the Java backend's Flyway migrations V1..V22 leave
-- behind, so an existing 2.x database already matches it and is left untouched:
-- every statement is CREATE TABLE IF NOT EXISTS, and this version never alters
-- a table it did not create. Upgrading from 2.x means pointing this build at
-- the database you already have.
--
-- That is why the columns are as permissive as they are. Most are nullable
-- because Hibernate made them so, not because absent and empty differ; the row
-- structs in ../rows.go carry sql.Null* to match, and the repositories map NULL
-- to an unset value on the way out. Tightening any of this would break exactly
-- the databases this file exists to stay compatible with.
--
-- Derived by running V1..V22 against MySQL and dumping the result, not by hand.

-- +goose Up
CREATE TABLE IF NOT EXISTS `application` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `namespace` varchar(255) DEFAULT NULL,
  `owner` varchar(255) DEFAULT NULL,
  `icon` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_application_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_alert_state` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `environment` varchar(255) DEFAULT NULL,
  `metric` varchar(32) DEFAULT NULL,
  `firing` bit(1) NOT NULL DEFAULT b'0',
  `firing_since` datetime(6) DEFAULT NULL,
  `last_notified_time` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_state_target` (`namespace`(64),`application_name`(64),`environment`(64),`metric`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_build_config` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `build_image` varchar(255) DEFAULT NULL,
  `environment_configs` text,
  `namespace` varchar(255) DEFAULT NULL,
  `source_type` varchar(255) DEFAULT NULL,
  `docker_file_config` text,
  `source_config` text,
  PRIMARY KEY (`id`),
  KEY `idx_application_build_config_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_collaborator` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_application_collaborator` (`namespace`,`application_name`,`user_id`),
  KEY `idx_application_collaborator_app` (`namespace`,`application_name`),
  KEY `idx_application_collaborator_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_environment` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `environment` varchar(255) DEFAULT NULL,
  `namespace` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_application_environment_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_expert_config` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `environment_configs` text,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_runtime_spec` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `environment_configs` text,
  `namespace` varchar(255) DEFAULT NULL,
  `health_check` text,
  PRIMARY KEY (`id`),
  KEY `idx_application_runtime_spec_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_service_config` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `environment_configs` text,
  `namespace` varchar(255) DEFAULT NULL,
  `port` int DEFAULT NULL,
  `internal_ports` text,
  PRIMARY KEY (`id`),
  KEY `idx_application_service_config_app` (`namespace`,`application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `domain` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `cert_mode` varchar(255) DEFAULT NULL,
  `cert_not_after` datetime(6) DEFAULT NULL,
  `cert_pem` text,
  `cert_subject` varchar(255) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `host` varchar(255) DEFAULT NULL,
  `https` tinyint(1) DEFAULT NULL,
  `key_pem` text,
  `environment` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain_host` (`host`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `environment` (
  `id` varchar(255) NOT NULL,
  `build_storage_class` varchar(255) DEFAULT NULL,
  `image_repository_password` varchar(255) DEFAULT NULL,
  `image_repository_url` varchar(255) DEFAULT NULL,
  `image_repository_username` varchar(255) DEFAULT NULL,
  `api_server_token` text,
  `api_server_url` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `work_namespace` varchar(255) DEFAULT NULL,
  `git_credential` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_environment_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `external_account` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `provider` varchar(255) DEFAULT NULL,
  `provider_user_id` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_external_account_provider_subject` (`provider`,`provider_user_id`),
  KEY `idx_external_account_provider_user` (`provider`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `namespace` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `pipeline` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `namespace` varchar(255) DEFAULT NULL,
  `application_name` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `artifact` text,
  `environment` varchar(255) DEFAULT NULL,
  `publish_type` varchar(255) DEFAULT NULL,
  `deploy_mode` varchar(255) DEFAULT NULL,
  `operator_id` varchar(255) DEFAULT NULL,
  `message` text,
  `trigger_type` varchar(32) DEFAULT 'RELEASE',
  `rollback_from_pipeline_id` varchar(255) DEFAULT NULL,
  `publish_config` text,
  PRIMARY KEY (`id`),
  KEY `idx_pipeline_status` (`status`),
  KEY `idx_pipeline_app_created` (`namespace`,`application_name`,`created_time`),
  KEY `idx_pipeline_app_env_created` (`namespace`,`application_name`,`environment`,`created_time`),
  KEY `idx_pipeline_app_status_created` (`namespace`,`application_name`,`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `user` (
  `id` varchar(255) NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `role` varchar(255) DEFAULT NULL,
  `username` varchar(255) DEFAULT NULL,
  `access_token` varchar(255) DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_access_token` (`access_token`),
  KEY `idx_user_username` (`username`),
  KEY `idx_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- +goose Down
DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS `pipeline`;
DROP TABLE IF EXISTS `namespace`;
DROP TABLE IF EXISTS `external_account`;
DROP TABLE IF EXISTS `environment`;
DROP TABLE IF EXISTS `domain`;
DROP TABLE IF EXISTS `application_service_config`;
DROP TABLE IF EXISTS `application_runtime_spec`;
DROP TABLE IF EXISTS `application_expert_config`;
DROP TABLE IF EXISTS `application_environment`;
DROP TABLE IF EXISTS `application_collaborator`;
DROP TABLE IF EXISTS `application_build_config`;
DROP TABLE IF EXISTS `application_alert_state`;
DROP TABLE IF EXISTS `application`;
