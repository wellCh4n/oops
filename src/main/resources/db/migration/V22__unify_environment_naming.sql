-- Environments are referenced by name everywhere, and the reference is now
-- uniformly called "environment": no environment_name columns, no
-- environmentName keys inside the environment_configs JSON blobs. (The
-- environment table's own id stays unreferenced by anything else.)
--
-- The column renames are guarded because a database that ran the 3.0 Go
-- release already carries the new names -- that release renamed them from its
-- own migration history (goose), which left flyway_schema_history at V21. This
-- migration must therefore be a no-op there and do the work everywhere else.

SET @statement := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'application_alert_state'
             AND COLUMN_NAME = 'environment_name'),
    'ALTER TABLE application_alert_state RENAME COLUMN environment_name TO environment',
    'DO 0'));
PREPARE renameColumn FROM @statement;
EXECUTE renameColumn;
DEALLOCATE PREPARE renameColumn;

SET @statement := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'application_environment'
             AND COLUMN_NAME = 'environment_name'),
    'ALTER TABLE application_environment RENAME COLUMN environment_name TO environment',
    'DO 0'));
PREPARE renameColumn FROM @statement;
EXECUTE renameColumn;
DEALLOCATE PREPARE renameColumn;

SET @statement := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'domain'
             AND COLUMN_NAME = 'environment_name'),
    'ALTER TABLE domain RENAME COLUMN environment_name TO environment',
    'DO 0'));
PREPARE renameColumn FROM @statement;
EXECUTE renameColumn;
DEALLOCATE PREPARE renameColumn;

-- The JSON rewrites match on the quoted key, so a stored value that happens to
-- read "environmentName" is left alone, and a blob already rewritten by the 3.0
-- release no longer matches at all.
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
