-- The whole schema, created in one step, and the only description of it there
-- is. OOPS 4.0 is a new install: there is no earlier database to migrate from,
-- so there is no history to replay.
--
-- Optional string columns are `NOT NULL DEFAULT ''` rather than nullable: for a
-- name, a description or an image tag, "absent" and "empty" are the same state,
-- and making the database say so is what keeps `sql.NullString` out of every
-- struct field and every mapping function. Three kinds of column keep their
-- NULL, because for them the two states really do differ:
--
--   * datetime — there is no empty instant, so an unset one has to be NULL;
--   * user.access_token — a unique key permits many NULLs but only one '',
--     so '' would let just one account exist without a token;
--   * application_service_config.port — port 0 is not a port, but neither is it
--     "unset", and the deploy path branches on which of the two it is.

-- +goose Up
CREATE TABLE IF NOT EXISTS `application` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `description` varchar(255) NOT NULL DEFAULT '',
    `name` varchar(255) NOT NULL DEFAULT '',
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `owner` varchar(255) NOT NULL DEFAULT '',
    `icon` varchar(32) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_application_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `application_build_config` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `build_image` varchar(255) NOT NULL DEFAULT '',
    `environment_configs` text NOT NULL,
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `source_type` varchar(255) NOT NULL DEFAULT '',
    `docker_file_config` text NOT NULL,
    `source_config` text NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_application_build_config_app` (`namespace`, `application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `application_environment` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `environment` varchar(255) NOT NULL DEFAULT '',
    `namespace` varchar(255) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    KEY `idx_application_environment_app` (`namespace`, `application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `application_runtime_spec` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `environment_configs` text NOT NULL,
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `health_check` text NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_application_runtime_spec_app` (`namespace`, `application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `application_service_config` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `environment_configs` text NOT NULL,
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `port` int DEFAULT NULL,
    `internal_ports` text NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_application_service_config_app` (`namespace`, `application_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `application_expert_config` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `environment_configs` text NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `application_collaborator` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `user_id` varchar(255) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_application_collaborator` (`namespace`, `application_name`, `user_id`),
    KEY `idx_application_collaborator_app` (`namespace`, `application_name`),
    KEY `idx_application_collaborator_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `application_alert_state` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `environment` varchar(255) NOT NULL DEFAULT '',
    `metric` varchar(32) NOT NULL DEFAULT '',
    `firing` bit(1) NOT NULL DEFAULT b'0',
    `firing_since` datetime(6) DEFAULT NULL,
    `last_notified_time` datetime(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert_state_target` (`namespace`(64), `application_name`(64), `environment`(64), `metric`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `domain` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `cert_mode` varchar(255) NOT NULL DEFAULT '',
    `cert_not_after` datetime(6) DEFAULT NULL,
    `cert_pem` text NOT NULL,
    `cert_subject` varchar(255) NOT NULL DEFAULT '',
    `description` varchar(255) NOT NULL DEFAULT '',
    `host` varchar(255) NOT NULL DEFAULT '',
    `https` boolean NOT NULL DEFAULT 0,
    `key_pem` text NOT NULL,
    `environment` varchar(255) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain_host` (`host`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `environment` (
    `id` varchar(255) NOT NULL,
    `build_storage_class` varchar(255) NOT NULL DEFAULT '',
    `image_repository_password` varchar(255) NOT NULL DEFAULT '',
    `image_repository_url` varchar(255) NOT NULL DEFAULT '',
    `image_repository_username` varchar(255) NOT NULL DEFAULT '',
    `api_server_token` text NOT NULL,
    `api_server_url` varchar(255) NOT NULL DEFAULT '',
    `name` varchar(255) NOT NULL DEFAULT '',
    `work_namespace` varchar(255) NOT NULL DEFAULT '',
    `git_credential` text NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_environment_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `external_account` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `email` varchar(255) NOT NULL DEFAULT '',
    `provider` varchar(255) NOT NULL DEFAULT '',
    `provider_user_id` varchar(255) NOT NULL DEFAULT '',
    `user_id` varchar(255) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    KEY `idx_external_account_provider_subject` (`provider`, `provider_user_id`),
    KEY `idx_external_account_provider_user` (`provider`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `namespace` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `description` varchar(255) NOT NULL DEFAULT '',
    `name` varchar(255) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `pipeline` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) NOT NULL DEFAULT '',
    `application_name` varchar(255) NOT NULL DEFAULT '',
    `status` varchar(255) NOT NULL DEFAULT '',
    `artifact` text NOT NULL,
    `environment` varchar(255) NOT NULL DEFAULT '',
    `publish_type` varchar(255) NOT NULL DEFAULT '',
    `deploy_mode` varchar(255) NOT NULL DEFAULT '',
    `operator_id` varchar(255) NOT NULL DEFAULT '',
    `message` text NOT NULL,
    `trigger_type` varchar(32) NOT NULL DEFAULT 'RELEASE',
    `rollback_from_pipeline_id` varchar(255) NOT NULL DEFAULT '',
    `publish_config` text NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_pipeline_status` (`status`),
    KEY `idx_pipeline_app_created` (`namespace`, `application_name`, `created_time`),
    KEY `idx_pipeline_app_env_created` (`namespace`, `application_name`, `environment`, `created_time`),
    KEY `idx_pipeline_app_status_created` (`namespace`, `application_name`, `status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `email` varchar(255) NOT NULL DEFAULT '',
    `password` varchar(255) NOT NULL DEFAULT '',
    `role` varchar(255) NOT NULL DEFAULT '',
    `username` varchar(255) NOT NULL DEFAULT '',
    `access_token` varchar(255) DEFAULT NULL,
    `enabled` boolean NOT NULL DEFAULT 1,
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
DROP TABLE IF EXISTS `application_alert_state`;
DROP TABLE IF EXISTS `application_collaborator`;
DROP TABLE IF EXISTS `application_expert_config`;
DROP TABLE IF EXISTS `application_service_config`;
DROP TABLE IF EXISTS `application_runtime_spec`;
DROP TABLE IF EXISTS `application_environment`;
DROP TABLE IF EXISTS `application_build_config`;
DROP TABLE IF EXISTS `application`;
