-- Edge-trigger state for the resource alerts: one row per application + environment + metric.
--
-- The scan job re-evaluates every minute, so without somewhere to remember the previous answer a problem that lasts
-- an afternoon would send one message per minute. Persisted rather than in-memory so a restart does not re-announce
-- everything that was already known to be firing.
CREATE TABLE `application_alert_state` (
    `id` varchar(255) NOT NULL,
    `created_time` datetime(6) DEFAULT NULL,
    `namespace` varchar(255) DEFAULT NULL,
    `application_name` varchar(255) DEFAULT NULL,
    `environment_name` varchar(255) DEFAULT NULL,
    `metric` varchar(32) DEFAULT NULL,
    `firing` bit(1) NOT NULL DEFAULT b'0',
    `firing_since` datetime(6) DEFAULT NULL,
    `last_notified_time` datetime(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    -- Prefix lengths, not whole columns: four varchar(255) columns under utf8mb4 come to 4080 bytes and InnoDB caps an
    -- index at 3072. 64 characters is comfortably above the 63-character ceiling Kubernetes puts on the names that
    -- actually go in here, so the prefix cannot truncate two distinct targets into one.
    UNIQUE KEY `uk_alert_state_target` (`namespace`(64), `application_name`(64), `environment_name`(64), `metric`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
