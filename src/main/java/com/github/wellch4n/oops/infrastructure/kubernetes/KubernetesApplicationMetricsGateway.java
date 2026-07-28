package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.dto.PodMetricSnapshot;
import com.github.wellch4n.oops.application.port.ApplicationMetricsGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.OopsTypes;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KubernetesApplicationMetricsGateway implements ApplicationMetricsGateway {

    private static final String APPLICATION_TYPE_LABEL = "oops.type";
    private static final String APPLICATION_NAME_LABEL = "oops.app.name";
    private static final BigDecimal MILLIS_PER_CORE = BigDecimal.valueOf(1000);

    private final KubernetesClientPool clientPool;

    public KubernetesApplicationMetricsGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public List<PodMetricSnapshot> getCurrentMetrics(Environment environment,
                                                     String namespace,
                                                     String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());

        Set<String> applicationPodNames = client.pods()
                .inNamespace(namespace)
                .withLabel(APPLICATION_TYPE_LABEL, OopsTypes.APPLICATION.name())
                .withLabel(APPLICATION_NAME_LABEL, applicationName)
                .list().getItems().stream()
                .map(pod -> pod.getMetadata().getName())
                .collect(Collectors.toSet());
        if (applicationPodNames.isEmpty()) {
            return List.of();
        }

        try {
            return client.top().pods().metrics(namespace).getItems().stream()
                    .filter(podMetrics -> applicationPodNames.contains(podMetrics.getMetadata().getName()))
                    .map(this::toSnapshot)
                    .sorted(Comparator.comparing(PodMetricSnapshot::podName))
                    .toList();
        } catch (Exception exception) {
            // metrics-server may be absent on this cluster — degrade to an empty reading.
            log.warn("Failed to read pod metrics for {}/{}: {}", namespace, applicationName, exception.getMessage());
            return List.of();
        }
    }

    private PodMetricSnapshot toSnapshot(PodMetrics podMetrics) {
        long cpuMillis = 0L;
        long memoryBytes = 0L;
        for (ContainerMetrics container : podMetrics.getContainers()) {
            Map<String, Quantity> usage = container.getUsage();
            if (usage == null) {
                continue;
            }
            Quantity cpu = usage.get("cpu");
            Quantity memory = usage.get("memory");
            if (cpu != null) {
                cpuMillis += Quantity.getAmountInBytes(cpu).multiply(MILLIS_PER_CORE).longValue();
            }
            if (memory != null) {
                memoryBytes += Quantity.getAmountInBytes(memory).longValue();
            }
        }
        return new PodMetricSnapshot(podMetrics.getMetadata().getName(), cpuMillis, memoryBytes);
    }
}
