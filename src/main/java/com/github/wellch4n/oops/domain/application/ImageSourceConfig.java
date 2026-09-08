package com.github.wellch4n.oops.domain.application;

/**
 * Prebuilt-image build source. {@code image} is the image name without a tag or digest
 * (e.g. {@code ghcr.io/org/app}); the tag is chosen per publish and lives on the pipeline's
 * {@code ImagePublishConfig}, mirroring how a Git branch is chosen per publish.
 *
 * <p>Named {@code image} rather than {@code repository} because the two are edited and stored
 * separately — an application that switches between GIT and IMAGE keeps both values, instead of
 * showing a Git URL in the image box.
 */
public record ImageSourceConfig(String image) implements SourceConfig {
}
