package com.github.wellch4n.oops.domain.application;

/**
 * Prebuilt-image build source. {@code repository} is the image name without a tag or digest
 * (e.g. {@code ghcr.io/org/app}); the tag is chosen per publish and lives on the pipeline's
 * {@code ImagePublishConfig}, mirroring how a Git branch is chosen per publish.
 */
public record ImageSourceConfig(String repository) implements SourceConfig {
}
