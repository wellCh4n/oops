package com.github.wellch4n.oops.domain.application;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Source-type-specific build parameters captured on an application's build config. Serialized as a
 * JSON blob (column {@code source_config}); the {@code type} discriminator matches
 * {@code ApplicationSourceType} and the sibling {@code source_type} column.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GitSourceConfig.class, name = "GIT"),
        @JsonSubTypes.Type(value = ZipSourceConfig.class, name = "ZIP")
})
public sealed interface SourceConfig permits GitSourceConfig, ZipSourceConfig {
}
