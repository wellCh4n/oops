package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.PipelineLogGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class KubernetesPipelineLogGateway implements PipelineLogGateway {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SseEmitter watch(Pipeline pipeline, Environment environment) {
        SseEmitter emitter = new SseEmitter(0L);
        ConcurrentLinkedQueue<AutoCloseable> resources = new ConcurrentLinkedQueue<>();
        AtomicBoolean isClosed = new AtomicBoolean(false);

        Runnable cleanup = () -> {
            if (isClosed.compareAndSet(false, true)) {
                log.info("Closing resources for pipeline: {}", pipeline.getId());
                while (!resources.isEmpty()) {
                    try { resources.poll().close(); } catch (Exception _) {}
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.error("SSE error for pipeline {}: {}", pipeline.getId(), e.getMessage());
            cleanup.run();
        });

        Thread.startVirtualThread(() -> {
            try {
                KubernetesClient client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer());
                resources.add(client);

                String workNamespace = environment.getWorkNamespace();
                String jobName = pipeline.getName();

                Job job = client.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
                if (job == null) {
                    emitter.send(SseEmitter.event().name("error").data("Job not found"));
                    return;
                }

                List<String> containers = new ArrayList<>();
                var spec = job.getSpec().getTemplate().getSpec();
                if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> containers.add(c.getName()));
                if (spec.getContainers() != null) spec.getContainers().forEach(c -> containers.add(c.getName()));

                emitter.send(SseEmitter.event().name("steps").data(objectMapper.writeValueAsString(containers)));

                for (String containerName : containers) {
                    Pod pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                            .waitUntilCondition(Objects::nonNull, 2, TimeUnit.MINUTES);

                    client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                            .waitUntilCondition(p -> isContainerReady(p, containerName), 2, TimeUnit.MINUTES);

                    LogWatch logWatch = client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                            .inContainer(containerName)
                            .watchLog();
                    resources.add(logWatch);

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!isClosed.get() && (line = reader.readLine()) != null) {
                            emitter.send(SseEmitter.event().name(containerName).data(line));
                        }
                    } finally {
                        resources.remove(logWatch);
                        logWatch.close();
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("Pipeline error", e);
                emitter.completeWithError(e);
            } finally {
                log.info("Pipeline watch ended for: {}", pipeline.getId());
                cleanup.run();
            }
        });

        return emitter;
    }

    private boolean isContainerReady(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) return false;
        return Stream.concat(
                Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of()).stream(),
                Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of()).stream()
        ).anyMatch(s -> s.getName().equals(containerName) &&
                (s.getState().getRunning() != null || s.getState().getTerminated() != null));
    }
}
