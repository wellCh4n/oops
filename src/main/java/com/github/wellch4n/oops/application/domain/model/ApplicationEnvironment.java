package com.github.wellch4n.oops.application.domain.model;

import lombok.Data;

@Data
public class ApplicationEnvironment {
    private String id;

    private String namespace;

    private String applicationName;

    private String environmentName;
}
