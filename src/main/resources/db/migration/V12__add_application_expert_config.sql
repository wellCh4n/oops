CREATE TABLE `application_expert_config` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `environment_configs` text,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
