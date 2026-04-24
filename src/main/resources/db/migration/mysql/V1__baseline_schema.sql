CREATE TABLE `application` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `description` varchar(255) DEFAULT NULL,
    `name` varchar(255) DEFAULT NULL,
    `namespace` varchar(255) DEFAULT NULL,
    `owner` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_application_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `application_build_config` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `build_image` varchar(255) DEFAULT NULL,
    `docker_file` varchar(255) DEFAULT NULL,
    `environment_configs` text,
    `namespace` varchar(255) DEFAULT NULL,
    `repository` varchar(255) DEFAULT NULL,
    `source_type` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `application_environment` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `environment_name` varchar(255) DEFAULT NULL,
    `namespace` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `application_performance_config` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `environment_configs` text,
    `namespace` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `application_service_config` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `environment_configs` text,
    `namespace` varchar(255) DEFAULT NULL,
    `port` int DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `domain` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `cert_mode` varchar(255) DEFAULT NULL,
    `cert_not_after` datetime(6) DEFAULT NULL,
    `cert_pem` text,
    `cert_subject` varchar(255) DEFAULT NULL,
    `description` varchar(255) DEFAULT NULL,
    `host` varchar(255) DEFAULT NULL,
    `https` boolean DEFAULT NULL,
    `key_pem` text,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain_host` (`host`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `environment` (
    `id` varchar(255) NOT NULL,
    `build_storage_class` varchar(255) DEFAULT NULL,
    `image_repository_password` varchar(255) DEFAULT NULL,
    `image_repository_url` varchar(255) DEFAULT NULL,
    `image_repository_username` varchar(255) DEFAULT NULL,
    `api_server_token` text,
    `api_server_url` varchar(255) DEFAULT NULL,
    `name` varchar(255) DEFAULT NULL,
    `work_namespace` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_environment_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `external_account` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `email` varchar(255) DEFAULT NULL,
    `provider` varchar(255) DEFAULT NULL,
    `provider_user_id` varchar(255) DEFAULT NULL,
    `user_id` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `namespace` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `description` varchar(255) DEFAULT NULL,
    `name` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pipeline` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `status` varchar(255) DEFAULT NULL,
    `artifact` text,
    `environment` varchar(255) DEFAULT NULL,
    `branch` varchar(255) DEFAULT NULL,
    `publish_type` varchar(255) DEFAULT NULL,
    `publish_repository` varchar(255) DEFAULT NULL,
    `deploy_mode` varchar(255) DEFAULT NULL,
    `operator_id` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `email` varchar(255) NOT NULL,
    `password` varchar(255) DEFAULT NULL,
    `role` varchar(255) DEFAULT NULL,
    `username` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
