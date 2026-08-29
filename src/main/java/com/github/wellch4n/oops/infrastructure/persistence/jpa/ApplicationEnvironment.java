package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class ApplicationEnvironment extends BaseDataObject {

    private String namespace;

    private String applicationName;

    private String environmentName;
}
