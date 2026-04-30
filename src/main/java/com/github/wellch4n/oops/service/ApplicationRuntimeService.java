package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpecRepository;
import com.github.wellch4n.oops.domain.event.RuntimeSpecChangedEvent;
import com.github.wellch4n.oops.exception.BizException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ApplicationRuntimeService {

    private final ApplicationRuntimeSpecRepository runtimeSpecRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ApplicationRuntimeService(ApplicationRuntimeSpecRepository runtimeSpecRepository,
                                     ApplicationEventPublisher eventPublisher) {
        this.runtimeSpecRepository = runtimeSpecRepository;
        this.eventPublisher = eventPublisher;
    }

    public List<ApplicationRuntimeSpec.EnvironmentConfig> getApplicationRuntimeSpecEnvironmentConfigs(String namespace, String name) {
        ApplicationRuntimeSpec runtimeSpec = runtimeSpecRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (runtimeSpec == null || runtimeSpec.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return runtimeSpec.getEnvironmentConfigs();
    }

    public ApplicationRuntimeSpec getApplicationRuntimeSpec(String namespace, String name) {
        ApplicationRuntimeSpec runtimeSpec = runtimeSpecRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (runtimeSpec == null) {
            runtimeSpec = new ApplicationRuntimeSpec();
            runtimeSpec.setNamespace(namespace);
            runtimeSpec.setApplicationName(name);
            runtimeSpec.setEnvironmentConfigs(Collections.emptyList());
        }
        runtimeSpec.setHealthCheck(normalizeHealthCheck(runtimeSpec.getHealthCheck()));
        return runtimeSpec;
    }

    @Transactional
    public Boolean updateApplicationRuntimeSpecEnvironmentConfigs(String namespace, String appName, List<ApplicationRuntimeSpec.EnvironmentConfig> configs) {
        ApplicationRuntimeSpec existing = runtimeSpecRepository.findByNamespaceAndApplicationName(namespace, appName).orElse(null);
        ApplicationRuntimeSpec request = new ApplicationRuntimeSpec();
        request.setEnvironmentConfigs(configs);
        request.setHealthCheck(existing != null ? existing.getHealthCheck() : null);
        return updateApplicationRuntimeSpec(namespace, appName, request);
    }

    @Transactional
    public Boolean updateApplicationRuntimeSpec(String namespace, String appName, ApplicationRuntimeSpec request) {
        ApplicationRuntimeSpec runtimeSpec = runtimeSpecRepository.findByNamespaceAndApplicationName(namespace, appName)
                .orElseGet(() -> {
                    ApplicationRuntimeSpec config = new ApplicationRuntimeSpec();
                    config.setNamespace(namespace);
                    config.setApplicationName(appName);
                    return config;
                });

        List<ApplicationRuntimeSpec.EnvironmentConfig> configs = request.getEnvironmentConfigs() != null
                ? request.getEnvironmentConfigs()
                : Collections.emptyList();
        List<ApplicationRuntimeSpec.EnvironmentConfig> existingConfigs =
                runtimeSpec.getEnvironmentConfigs() != null ? runtimeSpec.getEnvironmentConfigs() : Collections.emptyList();

        runtimeSpec.setEnvironmentConfigs(configs);
        runtimeSpec.setHealthCheck(request.getHealthCheck());
        runtimeSpecRepository.save(runtimeSpec);

        applyRuntimeSpecEnvironmentConfigUpdates(namespace, appName, configs, existingConfigs);

        return true;
    }

    private void applyRuntimeSpecEnvironmentConfigUpdates(String namespace,
                                                          String appName,
                                                          List<ApplicationRuntimeSpec.EnvironmentConfig> configs,
                                                          List<ApplicationRuntimeSpec.EnvironmentConfig> existingConfigs) {
        List<RuntimeSpecChangedEvent.RuntimeSpecChange> changes = new ArrayList<>();
        for (ApplicationRuntimeSpec.EnvironmentConfig config : configs) {
            ApplicationRuntimeSpec.EnvironmentConfig existing = existingConfigs.stream()
                    .filter(c -> c.getEnvironmentName().equals(config.getEnvironmentName()))
                    .findFirst().orElse(null);

            boolean replicasChanged = config.getReplicas() != null
                    && !config.getReplicas().equals(existing != null ? existing.getReplicas() : null);
            boolean resourcesChanged = !StringUtils.equals(config.getCpuRequest(), existing != null ? existing.getCpuRequest() : null)
                    || !StringUtils.equals(config.getCpuLimit(), existing != null ? existing.getCpuLimit() : null)
                    || !StringUtils.equals(config.getMemoryRequest(), existing != null ? existing.getMemoryRequest() : null)
                    || !StringUtils.equals(config.getMemoryLimit(), existing != null ? existing.getMemoryLimit() : null);

            if (replicasChanged || resourcesChanged) {
                changes.add(new RuntimeSpecChangedEvent.RuntimeSpecChange(
                        config.getEnvironmentName(), replicasChanged, resourcesChanged));
            }
        }

        if (!changes.isEmpty()) {
            eventPublisher.publishEvent(new RuntimeSpecChangedEvent(namespace, appName, changes));
        }
    }

    private ApplicationRuntimeSpec.HealthCheck normalizeHealthCheck(ApplicationRuntimeSpec.HealthCheck healthCheck) {
        ApplicationRuntimeSpec.HealthCheck normalized = healthCheck != null
                ? healthCheck
                : new ApplicationRuntimeSpec.HealthCheck();
        normalized.setEnabled(Boolean.TRUE.equals(normalized.getEnabled()));
        if (normalized.getPath() == null || normalized.getPath().isBlank()) {
            if (Boolean.TRUE.equals(normalized.getEnabled())) {
                throw new BizException("Health check path is required");
            }
            normalized.setPath(ApplicationRuntimeSpec.HealthCheck.DEFAULT_PATH);
        } else if (!normalized.getPath().startsWith("/")) {
            normalized.setPath("/" + normalized.getPath());
        }
        if (normalized.getInitialDelaySeconds() == null || normalized.getInitialDelaySeconds() < 0) {
            normalized.setInitialDelaySeconds(ApplicationRuntimeSpec.HealthCheck.DEFAULT_INITIAL_DELAY_SECONDS);
        }
        if (normalized.getPeriodSeconds() == null || normalized.getPeriodSeconds() <= 0) {
            normalized.setPeriodSeconds(ApplicationRuntimeSpec.HealthCheck.DEFAULT_PERIOD_SECONDS);
        }
        if (normalized.getTimeoutSeconds() == null || normalized.getTimeoutSeconds() <= 0) {
            normalized.setTimeoutSeconds(ApplicationRuntimeSpec.HealthCheck.DEFAULT_TIMEOUT_SECONDS);
        }
        if (normalized.getFailureThreshold() == null || normalized.getFailureThreshold() <= 0) {
            normalized.setFailureThreshold(ApplicationRuntimeSpec.HealthCheck.DEFAULT_FAILURE_THRESHOLD);
        }
        return normalized;
    }
}
