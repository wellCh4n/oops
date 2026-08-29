CREATE TABLE `application_collaborator` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `user_id` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_application_collaborator` (`namespace`, `application_name`, `user_id`),
    KEY `idx_application_collaborator_app` (`namespace`, `application_name`),
    KEY `idx_application_collaborator_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
