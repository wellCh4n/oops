-- Add a list of cluster-internal ports to application_service_config.
-- These ports are exposed only on the ClusterIP Service (and as container ports),
-- reachable via the internal domain `appname.namespace.svc.cluster.local:<port>`.
-- Stored as a JSON array of integers (e.g. [9090,50051]); NULL means none configured.
ALTER TABLE `application_service_config` ADD COLUMN `internal_ports` text DEFAULT NULL;
