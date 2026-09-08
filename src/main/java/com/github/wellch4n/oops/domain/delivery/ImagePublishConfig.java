package com.github.wellch4n.oops.domain.delivery;

/**
 * Image publish parameters: the image name from the build config and the tag the operator chose.
 * Kept split (rather than only the joined artifact) so the publish page can offer the last tag back,
 * the way it offers the last Git branch.
 */
public record ImagePublishConfig(String repository, String tag) implements PublishConfig {

    public String artifact() {
        return repository + ":" + tag;
    }
}
