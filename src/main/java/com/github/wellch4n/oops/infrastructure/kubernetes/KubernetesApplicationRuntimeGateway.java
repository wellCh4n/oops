package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.domain.shared.OopsTypes;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.ApplicationPodStatusView;
import com.github.wellch4n.oops.application.dto.DeploymentHealth;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.StatefulSetProcessor;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class KubernetesApplicationRuntimeGateway implements ApplicationRuntimeGateway {

    private static final String CLUSTER_SUFFIX = "cluster.local";
    private static final String CLUSTER_DOMAIN_FORMAT = "%s.%s.svc.%s";

    private final KubernetesClientPool clientPool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KubernetesApplicationRuntimeGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public void deleteWorkload(Environment environment, String namespace, String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        var statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(applicationName)
                .get();
        if (statefulSet != null) {
            client.apps().statefulSets()
                    .inNamespace(namespace)
                    .withName(applicationName)
                    .delete();
        }
    }

    @Override
    public void applyRuntimeSpec(Environment environment,
                                 String namespace,
                                 String applicationName,
                                 ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        if (runtimeSpec.getReplicas() != null) {
            client.apps().statefulSets()
                    .inNamespace(namespace)
                    .withName(applicationName)
                    .scale(runtimeSpec.getReplicas());
        }

        if (!hasResource(runtimeSpec)) {
            return;
        }
        var resources = buildResources(runtimeSpec);
        client.apps().statefulSets().inNamespace(namespace).withName(applicationName)
                .edit(statefulSet -> {
                    statefulSet.getSpec().getTemplate().getSpec().getContainers()
                            .forEach(container -> container.setResources(resources));
                    return statefulSet;
                });
    }

    private ResourceRequirements buildResources(ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec) {
        var resourcesBuilder = new ResourceRequirementsBuilder();
        if (StringUtils.isNotBlank(runtimeSpec.getCpuRequest())) {
            resourcesBuilder.addToRequests("cpu", new Quantity(runtimeSpec.getCpuRequest()));
        }
        if (StringUtils.isNotBlank(runtimeSpec.getCpuLimit())) {
            resourcesBuilder.addToLimits("cpu", new Quantity(runtimeSpec.getCpuLimit()));
        }
        if (StringUtils.isNotBlank(runtimeSpec.getMemoryRequest())) {
            resourcesBuilder.addToRequests("memory", new Quantity(runtimeSpec.getMemoryRequest() + "Mi"));
        }
        if (StringUtils.isNotBlank(runtimeSpec.getMemoryLimit())) {
            resourcesBuilder.addToLimits("memory", new Quantity(runtimeSpec.getMemoryLimit() + "Mi"));
        }
        return resourcesBuilder.build();
    }

    @Override
    public List<ApplicationPodStatusView> getPodStatuses(Environment environment, String namespace, String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        var pods = client.pods()
                .inNamespace(namespace)
                .withLabel("oops.type", OopsTypes.APPLICATION.name())
                .withLabel("oops.app.name", applicationName)
                .list();
        return pods.getItems().stream().map(this::toView).toList();
    }

    @Override
    public SseEmitter watchPodStatuses(Environment environment, String namespace, String applicationName) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        Map<String, ApplicationPodStatusView> state = new ConcurrentHashMap<>();

        // Non-pooled client: lifecycle tied to this SSE connection
        KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer());

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                try { client.close(); } catch (Exception _) {}
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> {
            log.warn("SSE error watching pod statuses for {}/{}: {}", namespace, applicationName, error.getMessage());
            cleanup.run();
        });

        // Heartbeat: keeps the connection warm through idle proxies and lets us
        // detect dead clients within ~25s (a failed send triggers cleanup).
        Thread.startVirtualThread(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(25_000);
                    if (closed.get()) {
                        return;
                    }
                    emitter.send(SseEmitter.event().name("heartbeat").data(""));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception _) {
                    // Receiver disconnected — emitter callbacks will clean up
                    return;
                }
            }
        });

        Thread.startVirtualThread(() -> {
            try {
                var podsResource = client.pods()
                        .inNamespace(namespace)
                        .withLabel("oops.type", OopsTypes.APPLICATION.name())
                        .withLabel("oops.app.name", applicationName);

                var initial = podsResource.list();
                for (Pod pod : initial.getItems()) {
                    state.put(pod.getMetadata().getName(), toView(pod));
                }
                send(emitter, state.values());

                Watch watch = podsResource.watch(new Watcher<>() {
                    @Override
                    public void eventReceived(Action action, Pod pod) {
                        if (closed.get()) {
                            return;
                        }
                        String podName = pod.getMetadata().getName();
                        switch (action) {
                            case ADDED, MODIFIED -> state.put(podName, toView(pod));
                            case DELETED -> state.remove(podName);
                            default -> {
                                return;
                            }
                        }
                        send(emitter, state.values());
                    }

                    @Override
                    public void onClose(WatcherException cause) {
                        if (cause != null) {
                            log.info("Pod watch closed for {}/{}: {}", namespace, applicationName, cause.getMessage());
                            emitter.completeWithError(cause);
                        } else {
                            emitter.complete();
                        }
                        cleanup.run();
                    }
                });

                emitter.onCompletion(() -> {
                    try { watch.close(); } catch (Exception _) {}
                });
                emitter.onTimeout(() -> {
                    try { watch.close(); } catch (Exception _) {}
                });
                emitter.onError(_ -> {
                    try { watch.close(); } catch (Exception _) {}
                });
            } catch (Exception e) {
                log.error("Failed to start pod status watch for {}/{}", namespace, applicationName, e);
                emitter.completeWithError(e);
                cleanup.run();
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, java.util.Collection<ApplicationPodStatusView> snapshot) {
        try {
            emitter.send(SseEmitter.event().name("status").data(objectMapper.writeValueAsString(snapshot)));
        } catch (Exception e) {
            // Receiver disconnected — let the emitter callbacks handle cleanup
            emitter.completeWithError(e);
        }
    }

    private ApplicationPodStatusView toView(Pod pod) {
        var view = new ApplicationPodStatusView();
        view.setName(pod.getMetadata().getName());
        view.setNamespace(pod.getMetadata().getNamespace());
        view.setPodIP(pod.getStatus() != null ? pod.getStatus().getPodIP() : null);
        view.setStatus(pod.getStatus() != null ? pod.getStatus().getPhase() : null);
        view.setNodeName(pod.getSpec() != null ? pod.getSpec().getNodeName() : null);
        List<ApplicationPodStatusView.ContainerStatus> containers = new ArrayList<>();
        var containerStatuses = pod.getStatus() != null ? pod.getStatus().getContainerStatuses() : null;
        if (containerStatuses != null) {
            for (var containerStatus : containerStatuses) {
                var container = new ApplicationPodStatusView.ContainerStatus();
                container.setName(containerStatus.getName());
                container.setImage(containerStatus.getImage());
                container.setReady(containerStatus.getReady());
                container.setRestartCount(containerStatus.getRestartCount());
                if (containerStatus.getState() != null && containerStatus.getState().getRunning() != null) {
                    container.setStartedAt(containerStatus.getState().getRunning().getStartedAt());
                }
                containers.add(container);
            }
        }
        view.setContainers(containers);
        return view;
    }

    @Override
    public void restartPod(Environment environment, String namespace, String podName) {
        clientPool.get(environment.getKubernetesApiServer())
                .pods().inNamespace(namespace).withName(podName).delete();
    }

    @Override
    public String findInternalServiceDomain(Environment environment, String namespace, String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        var services = client.services().inNamespace(namespace).withLabel("oops.app.name", applicationName).list().getItems();
        if (services.isEmpty()) {
            return null;
        }
        var service = services.getFirst();
        return String.format(CLUSTER_DOMAIN_FORMAT,
                service.getMetadata().getName(),
                service.getMetadata().getNamespace(),
                CLUSTER_SUFFIX);
    }

    @Override
    public String findCurrentImage(Environment environment, String namespace, String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        var statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(applicationName)
                .get();
        if (statefulSet == null
                || statefulSet.getSpec() == null
                || statefulSet.getSpec().getTemplate() == null
                || statefulSet.getSpec().getTemplate().getSpec() == null) {
            return null;
        }
        var containers = statefulSet.getSpec().getTemplate().getSpec().getContainers();
        return containers.stream()
                .filter(container -> applicationName.equals(container.getName()))
                .map(io.fabric8.kubernetes.api.model.Container::getImage)
                .findFirst()
                .orElseGet(() -> containers.isEmpty() ? null : containers.getFirst().getImage());
    }

    private static final java.util.Set<String> FATAL_WAITING_REASONS =
            java.util.Set.of("ImagePullBackOff", "ErrImagePull", "CrashLoopBackOff");

    @Override
    public DeploymentHealth getDeploymentHealth(Environment environment, String namespace, String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        var statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(applicationName)
                .get();
        if (statefulSet == null) {
            return new DeploymentHealth(true, false, null, null, null, null);
        }

        Integer desiredReplicas = statefulSet.getSpec() != null ? statefulSet.getSpec().getReplicas() : null;
        var status = statefulSet.getStatus();
        Integer readyReplicas = status != null ? status.getReadyReplicas() : null;
        Integer updatedReplicas = status != null ? status.getUpdatedReplicas() : null;
        Long generation = statefulSet.getMetadata() != null ? statefulSet.getMetadata().getGeneration() : null;
        Long observedGeneration = status != null ? status.getObservedGeneration() : null;

        int desired = desiredReplicas == null ? 0 : desiredReplicas;
        int ready = readyReplicas == null ? 0 : readyReplicas;
        int updated = updatedReplicas == null ? 0 : updatedReplicas;
        boolean generationObserved = generation == null
                || (observedGeneration != null && observedGeneration >= generation);
        boolean rolloutComplete = generationObserved && updated == desired && ready == desired;

        String failureReason = findFatalPodWaitingReason(client, namespace, applicationName);
        Instant notReadySince = rolloutComplete
                ? null
                : findRolloutNotReadySince(client, namespace, applicationName, statefulSet);

        return new DeploymentHealth(false, rolloutComplete, desiredReplicas, readyReplicas, failureReason, notReadySince);
    }

    private Instant findRolloutNotReadySince(
            KubernetesClient client,
            String namespace,
            String applicationName,
            io.fabric8.kubernetes.api.model.apps.StatefulSet statefulSet
    ) {
        String rolloutStartedAt = statefulSet.getMetadata() != null
                && statefulSet.getMetadata().getAnnotations() != null
                ? statefulSet.getMetadata().getAnnotations().get(StatefulSetProcessor.ROLLOUT_STARTED_AT_ANNOTATION)
                : null;
        Instant annotatedRolloutStartedAt = parseKubernetesInstant(rolloutStartedAt);
        if (annotatedRolloutStartedAt != null) {
            return annotatedRolloutStartedAt;
        }
        return findOldestNotReadyPodTime(client, namespace, applicationName).orElse(null);
    }

    private Optional<Instant> findOldestNotReadyPodTime(KubernetesClient client, String namespace, String applicationName) {
        var pods = client.pods()
                .inNamespace(namespace)
                .withLabel("oops.type", OopsTypes.APPLICATION.name())
                .withLabel("oops.app.name", applicationName)
                .list();
        return pods.getItems().stream()
                .map(this::notReadySince)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder());
    }

    private Instant notReadySince(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return podCreationTime(pod);
        }
        PodCondition readyCondition = pod.getStatus().getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .orElse(null);
        if (readyCondition == null || "True".equalsIgnoreCase(readyCondition.getStatus())) {
            return null;
        }
        Instant lastTransitionTime = parseKubernetesInstant(readyCondition.getLastTransitionTime());
        return lastTransitionTime != null ? lastTransitionTime : podCreationTime(pod);
    }

    private Instant podCreationTime(Pod pod) {
        return pod.getMetadata() == null ? null : parseKubernetesInstant(pod.getMetadata().getCreationTimestamp());
    }

    private Instant parseKubernetesInstant(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            log.debug("Failed to parse Kubernetes timestamp: {}", value, exception);
            return null;
        }
    }

    private String findFatalPodWaitingReason(KubernetesClient client, String namespace, String applicationName) {
        var pods = client.pods()
                .inNamespace(namespace)
                .withLabel("oops.type", OopsTypes.APPLICATION.name())
                .withLabel("oops.app.name", applicationName)
                .list();
        for (Pod pod : pods.getItems()) {
            if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                continue;
            }
            for (var containerStatus : pod.getStatus().getContainerStatuses()) {
                var state = containerStatus.getState();
                if (state != null && state.getWaiting() != null) {
                    String reason = state.getWaiting().getReason();
                    if (reason != null && FATAL_WAITING_REASONS.contains(reason)) {
                        return reason + " (" + pod.getMetadata().getName() + ")";
                    }
                }
            }
        }
        return null;
    }

    private boolean hasResource(ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec) {
        return StringUtils.isNotBlank(runtimeSpec.getCpuRequest())
                || StringUtils.isNotBlank(runtimeSpec.getCpuLimit())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryRequest())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryLimit());
    }
}
