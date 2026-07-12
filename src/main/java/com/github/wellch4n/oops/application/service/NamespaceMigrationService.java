package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.ConfigMapItem;
import com.github.wellch4n.oops.application.dto.NamespaceMigrationResult;
import com.github.wellch4n.oops.application.dto.UpdateConfigMapCommand;
import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.ConfigMapGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.application.port.repository.NamespaceRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationEnvironment;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.UserRole;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Moves an application from one namespace to another. Because the OOPS namespace doubles as the
 * Kubernetes namespace that workloads run in, a move is not a pure metadata change: the running
 * StatefulSet/Service/IngressRoute live in the old namespace and must be recreated in the new one.
 *
 * <p>The migration runs in three phases:
 * <ol>
 *     <li>Snapshot the live container image and ConfigMap of each bound environment in the old namespace.</li>
 *     <li>Move every database row (application aggregate + pipelines) to the target namespace.</li>
 *     <li>For each environment that had a running workload, recreate it in the target namespace using the
 *         captured image, then delete the old workload (its Service/IngressRoute/ConfigMap cascade away via
 *         their owner reference to the StatefulSet).</li>
 * </ol>
 *
 * @author wellCh4n
 */
@Slf4j
@Service
public class NamespaceMigrationService {

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;
    private final EnvironmentRepository environmentRepository;
    private final NamespaceRepository namespaceRepository;
    private final UserService userService;
    private final ApplicationRuntimeGateway applicationRuntimeGateway;
    private final ConfigMapGateway configMapGateway;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final DeploymentConcurrencyPolicy deploymentConcurrencyPolicy;

    public NamespaceMigrationService(ApplicationRepository applicationRepository,
                                     PipelineRepository pipelineRepository,
                                     EnvironmentRepository environmentRepository,
                                     NamespaceRepository namespaceRepository,
                                     UserService userService,
                                     ApplicationRuntimeGateway applicationRuntimeGateway,
                                     ConfigMapGateway configMapGateway,
                                     ArtifactDeploymentExecutor artifactDeploymentExecutor,
                                     DeploymentConcurrencyPolicy deploymentConcurrencyPolicy) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentRepository = environmentRepository;
        this.namespaceRepository = namespaceRepository;
        this.userService = userService;
        this.applicationRuntimeGateway = applicationRuntimeGateway;
        this.configMapGateway = configMapGateway;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.deploymentConcurrencyPolicy = deploymentConcurrencyPolicy;
    }

    public NamespaceMigrationResult migrateNamespace(String namespace,
                                                     String name,
                                                     String targetNamespace,
                                                     String currentUserId) {
        if (StringUtils.isBlank(targetNamespace)) {
            throw new BizException("Target namespace is required");
        }
        String target = targetNamespace.trim();
        if (target.equals(namespace)) {
            throw new BizException("Target namespace must be different from the current namespace");
        }

        Application application = applicationRepository.findAggregate(namespace, name);
        if (application == null) {
            throw new BizException("Application not found");
        }

        boolean isAdmin = userService.findById(currentUserId)
                .filter(user -> user.getRole() == UserRole.ADMIN)
                .isPresent();
        if (!isAdmin && !currentUserId.equals(application.getOwner())) {
            throw new BizException("Permission denied");
        }

        if (namespaceRepository.findFirstByName(target) == null) {
            throw new BizException("Target namespace not found: " + target);
        }
        if (applicationRepository.findAggregate(target, name) != null) {
            throw new BizException("An application named " + name + " already exists in namespace " + target);
        }

        deploymentConcurrencyPolicy.ensureNoActivePipeline(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                namespace, name, deploymentConcurrencyPolicy.activePipelineStatuses()
        ));

        List<String> migratedEnvironments = new ArrayList<>();
        List<String> failedEnvironments = new ArrayList<>();

        // Phase 1: snapshot the live workload (image + config map) of each bound environment in the OLD namespace.
        List<EnvironmentMigrationPlan> plans = new ArrayList<>();
        List<ApplicationEnvironment> bindings = application.getEnvironments() != null
                ? application.getEnvironments()
                : List.of();
        for (ApplicationEnvironment binding : bindings) {
            String environmentName = binding.getEnvironmentName();
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                continue;
            }
            try {
                String currentImage = applicationRuntimeGateway.findCurrentImage(environment, namespace, name);
                if (StringUtils.isBlank(currentImage)) {
                    // Bound but not currently running in this cluster; nothing to recreate.
                    continue;
                }
                List<ConfigMapItem> configMaps = configMapGateway.getConfigMaps(environment, namespace, name);
                plans.add(new EnvironmentMigrationPlan(environmentName, environment, currentImage, configMaps));
            } catch (Exception exception) {
                log.error("Failed to read live workload for {}/{} in env {}: {}",
                        namespace, name, environmentName, exception.getMessage(), exception);
                failedEnvironments.add(environmentName);
            }
        }

        // Phase 2: move all database rows to the target namespace.
        applicationRepository.migrateNamespace(namespace, target, name);
        pipelineRepository.migrateNamespace(namespace, target, name);
        Application migrated = applicationRepository.findAggregate(target, name);

        // Phase 3: recreate each running workload in the target namespace, then delete the old one.
        for (EnvironmentMigrationPlan plan : plans) {
            try {
                redeployToTarget(migrated, plan, target, currentUserId);
                applicationRuntimeGateway.deleteWorkload(plan.environment(), namespace, name);
                migratedEnvironments.add(plan.environmentName());
            } catch (Exception exception) {
                log.error("Failed to migrate workload for {}/{} env {} into namespace {}: {}",
                        namespace, name, plan.environmentName(), target, exception.getMessage(), exception);
                failedEnvironments.add(plan.environmentName());
            }
        }

        return new NamespaceMigrationResult(namespace, target, migratedEnvironments, failedEnvironments);
    }

    private void redeployToTarget(Application application,
                                  EnvironmentMigrationPlan plan,
                                  String target,
                                  String operatorUserId) {
        String applicationName = application.getName();
        String environmentName = plan.environmentName();

        // Copy environment variables first so the freshly created pods start with their configuration present.
        if (!plan.configMaps().isEmpty()) {
            configMapGateway.updateConfigMap(plan.environment(), target, applicationName, toUpdateCommands(plan.configMaps()));
        }

        Pipeline pipeline = Pipeline.initialize(
                target, applicationName, environmentName, application.sourceType(), DeployMode.IMMEDIATE, operatorUserId);
        pipeline.setArtifact(plan.currentImage());

        ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec = application.runtimeEnvironmentConfigOrDefault(environmentName);
        ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
        ApplicationServiceConfig serviceConfig = application.serviceConfigOrDefault();
        ApplicationExpertConfig.EnvironmentConfig expertConfig = application.expertEnvironmentConfigOrDefault(environmentName);

        artifactDeploymentExecutor.deploy(
                pipeline, application, plan.environment(), runtimeSpec, healthCheck, serviceConfig, expertConfig);

        // Re-apply the config map now that the StatefulSet exists so it inherits the owner reference for cascade cleanup.
        if (!plan.configMaps().isEmpty()) {
            configMapGateway.updateConfigMap(plan.environment(), target, applicationName, toUpdateCommands(plan.configMaps()));
        }
    }

    private List<UpdateConfigMapCommand> toUpdateCommands(List<ConfigMapItem> items) {
        // The snapshot from getConfigMaps() is already sorted by display order, and updateConfigMap()
        // re-derives each item's order from its position in this list, so preserving the iteration order
        // here carries the manual ordering across the migration. Group and comment must be copied explicitly.
        List<UpdateConfigMapCommand> commands = new ArrayList<>();
        for (ConfigMapItem item : items) {
            UpdateConfigMapCommand command = new UpdateConfigMapCommand();
            command.setKey(item.getKey());
            command.setValue(item.getValue());
            command.setSecret(item.isSecret());
            command.setMountPath(item.getMountPath());
            command.setGroup(item.getGroup());
            command.setComment(item.getComment());
            commands.add(command);
        }
        return commands;
    }

    private record EnvironmentMigrationPlan(
            String environmentName,
            Environment environment,
            String currentImage,
            List<ConfigMapItem> configMaps
    ) {
    }
}
