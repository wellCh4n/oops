package com.github.wellch4n.oops.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class ApplicationEnvironmentConfig extends BaseDataObject {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String namespace;

    private String applicationName;

    private String environmentName;

    private String buildCommand;

    private Integer replicas;

    private String cpuRequest;
    private String cpuLimit;

    private String memoryRequest;
    private String memoryLimit;
}
