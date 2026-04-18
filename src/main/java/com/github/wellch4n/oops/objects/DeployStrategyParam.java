package com.github.wellch4n.oops.objects;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.wellch4n.oops.enums.ApplicationSourceType;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GitDeployStrategyParam.class, name = "GIT"),
        @JsonSubTypes.Type(value = ZipDeployStrategyParam.class, name = "ZIP")
})
public sealed interface DeployStrategyParam permits GitDeployStrategyParam, ZipDeployStrategyParam {

    ApplicationSourceType getType();
}
