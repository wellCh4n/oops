-- +goose Up
-- Environments are referenced by name everywhere, and the reference is now
-- uniformly called "environment": no environment_name columns, no
-- environmentName keys inside the environment_configs JSON blobs. (The
-- environment table's own id stays unreferenced by anything else.)

ALTER TABLE application_alert_state RENAME COLUMN environment_name TO environment;
ALTER TABLE application_environment RENAME COLUMN environment_name TO environment;
ALTER TABLE domain RENAME COLUMN environment_name TO environment;

UPDATE application_build_config
  SET environment_configs = REPLACE(environment_configs, '"environmentName"', '"environment"')
  WHERE environment_configs LIKE '%"environmentName"%';
UPDATE application_runtime_spec
  SET environment_configs = REPLACE(environment_configs, '"environmentName"', '"environment"')
  WHERE environment_configs LIKE '%"environmentName"%';
UPDATE application_service_config
  SET environment_configs = REPLACE(environment_configs, '"environmentName"', '"environment"')
  WHERE environment_configs LIKE '%"environmentName"%';
UPDATE application_expert_config
  SET environment_configs = REPLACE(environment_configs, '"environmentName"', '"environment"')
  WHERE environment_configs LIKE '%"environmentName"%';
