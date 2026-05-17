package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.domain.shared.OopsTypes;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.ApplicationPodStatusView;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        var resources = resourcesBuilder.build();
        client.apps().statefulSets().inNamespace(namespace).withName(applicationName)
                .edit(statefulSet -> {
                    statefulSet.getSpec().getTemplate().getSpec().getContainers()
                            .forEach(container -> container.setResources(resources));
                    return statefulSet;
                });
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

    private boolean hasResource(ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec) {
        return StringUtils.isNotBlank(runtimeSpec.getCpuRequest())
                || StringUtils.isNotBlank(runtimeSpec.getCpuLimit())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryRequest())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryLimit());
    }
}
