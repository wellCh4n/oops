package com.github.wellch4n.oops.domain.application;

/**
 * ZIP build source. Currently carries no persistent parameters — the archive object key is presigned
 * per deploy and lives on the pipeline's publish config, not on the build config. Kept as a distinct
 * {@link SourceConfig} variant so ZIP-specific build settings can be added here later.
 */
public record ZipSourceConfig() implements SourceConfig {
}
