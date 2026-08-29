package com.github.wellch4n.oops.domain.delivery;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Source-type-specific publish parameters captured on a pipeline. Serialized as a JSON blob
 * (column {@code publish_config}); the {@code type} discriminator matches {@code ApplicationSourceType}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GitPublishConfig.class, name = "GIT"),
        @JsonSubTypes.Type(value = ZipPublishConfig.class, name = "ZIP")
})
public sealed interface PublishConfig permits GitPublishConfig, ZipPublishConfig {
}
