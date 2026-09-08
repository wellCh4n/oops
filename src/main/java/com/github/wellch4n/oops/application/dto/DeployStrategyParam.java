package com.github.wellch4n.oops.application.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GitDeployStrategyParam.class, name = "GIT"),
        @JsonSubTypes.Type(value = ZipDeployStrategyParam.class, name = "ZIP"),
        @JsonSubTypes.Type(value = ImageDeployStrategyParam.class, name = "IMAGE")
})
public sealed interface DeployStrategyParam permits GitDeployStrategyParam, ZipDeployStrategyParam, ImageDeployStrategyParam {

    ApplicationSourceType getType();
}
