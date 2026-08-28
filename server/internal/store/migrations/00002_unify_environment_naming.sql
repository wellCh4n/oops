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

-- +goose Down
-- Restores the 2.x names so a rollback to the last Java release can run
-- against this database again. The JSON key replacement matches the trailing
-- colon so a value that happens to equal "environment" is left alone.

ALTER TABLE application_alert_state RENAME COLUMN environment TO environment_name;
ALTER TABLE application_environment RENAME COLUMN environment TO environment_name;
ALTER TABLE domain RENAME COLUMN environment TO environment_name;

UPDATE application_build_config
  SET environment_configs = REPLACE(environment_configs, '"environment":', '"environmentName":')
  WHERE environment_configs LIKE '%"environment":%';
UPDATE application_runtime_spec
  SET environment_configs = REPLACE(environment_configs, '"environment":', '"environmentName":')
  WHERE environment_configs LIKE '%"environment":%';
UPDATE application_service_config
  SET environment_configs = REPLACE(environment_configs, '"environment":', '"environmentName":')
  WHERE environment_configs LIKE '%"environment":%';
UPDATE application_expert_config
  SET environment_configs = REPLACE(environment_configs, '"environment":', '"environmentName":')
  WHERE environment_configs LIKE '%"environment":%';
