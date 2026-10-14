-- The build configuration a pipeline was started with, captured when it is created so its build
-- summary keeps showing what the build really used after the application's config is edited.
-- The counterpart of publish_config: that one holds what the operator chose at publish time, this
-- one the slice of application_build_config that applied to the pipeline's environment.
--   {"buildVariables":[{"name":"NODE_ENV","value":"production"}, ...]}
-- NULL for pipelines that run no build (rollback, image publish) and for every pipeline created
-- before this column existed — nothing is backfilled, since what those builds used is not recorded.
ALTER TABLE `pipeline` ADD COLUMN `build_config` text DEFAULT NULL;
