package com.github.wellch4n.oops.infrastructure.kubernetes.sandbox;

import com.github.wellch4n.oops.application.port.SandboxExecutionGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients;
import com.github.wellch4n.oops.shared.util.NanoIdUtils;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class KubernetesSandboxExecutionGateway implements SandboxExecutionGateway {

    private static final String LABEL_APP = "app";
    private static final String LABEL_APP_VALUE = "oops-sandbox";
    private static final String LABEL_SANDBOX_ID = "oops.sandbox.id";
    private static final String LABEL_CREATED_BY = "oops.sandbox.created-by";

    @PostConstruct
    void silenceFabric8ShutdownNoise() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                "io.fabric8.kubernetes.client.informers.impl.cache.Reflector"))
                .setLevel(ch.qos.logback.classic.Level.OFF);
    }

    @Override
    public SseEmitter execute(Environment environment, SandboxJobSpec spec) {
        SseEmitter emitter = new SseEmitter((spec.timeoutSeconds() + 30L) * 1000L);
        String sandboxId = NanoIdUtils.generate();
        String jobName = "oops-sandbox-" + sandboxId;
        String workNamespace = environment.getWorkNamespace();

        Thread.startVirtualThread(() -> runSandbox(environment, spec, jobName, sandboxId, workNamespace, emitter));
        return emitter;
    }

    private void runSandbox(Environment environment, SandboxJobSpec spec,
                            String jobName, String sandboxId, String workNamespace, SseEmitter emitter) {
        try (KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer())) {
            client.batch().v1().jobs().inNamespace(workNamespace)
                    .resource(buildJob(spec, jobName, sandboxId, workNamespace)).create();

            Pod pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                    .waitUntilCondition(Objects::nonNull, 2, TimeUnit.MINUTES);
            String podName = pod.getMetadata().getName();
            client.pods().inNamespace(workNamespace).withName(podName)
                    .waitUntilCondition(KubernetesSandboxExecutionGateway::isContainerRunningOrTerminated, 2, TimeUnit.MINUTES);

            try (LogWatch logWatch = client.pods().inNamespace(workNamespace).withName(podName).watchLog()) {
                // Force EOF when the container terminates: K8s sometimes leaves /log?follow=true
                // open after the pod exits, leaving readLine() blocked forever.
                Thread.startVirtualThread(() -> {
                    try {
                        client.pods().inNamespace(workNamespace).withName(podName)
                                .waitUntilCondition(KubernetesSandboxExecutionGateway::isContainerTerminated, 10, TimeUnit.MINUTES);
                    } catch (Exception _) {
                    } finally {
                        logWatch.close();
                    }
                });

                BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
            } catch (Exception _) {
                // log stream closed (either by the terminator above or by a client disconnect) — fall through
            }

            Pod terminated = client.pods().inNamespace(workNamespace).withName(podName)
                    .waitUntilCondition(KubernetesSandboxExecutionGateway::isContainerTerminated, 1, TimeUnit.MINUTES);
            emitter.send(SseEmitter.event().name("exit").data(readExitCode(terminated)));
            emitter.complete();
        } catch (Exception exception) {
            log.warn("Sandbox execution {} ended abnormally: {}", jobName, exception.getMessage());
            emitter.completeWithError(exception);
        }
    }

    private Job buildJob(SandboxJobSpec spec, String jobName, String sandboxId, String workNamespace) {
        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_APP, LABEL_APP_VALUE);
        labels.put(LABEL_SANDBOX_ID, sandboxId);
        if (spec.createdByUserId() != null && !spec.createdByUserId().isBlank()) {
            labels.put(LABEL_CREATED_BY, spec.createdByUserId());
        }

        ResourceRequirements resources = new ResourceRequirements();
        resources.setRequests(Map.of("cpu", new Quantity(spec.cpuRequest()), "memory", new Quantity(spec.memoryRequest())));
        resources.setLimits(Map.of("cpu", new Quantity(spec.cpuLimit()), "memory", new Quantity(spec.memoryLimit())));

        return new JobBuilder()
                .withApiVersion("batch/v1").withKind("Job")
                .withNewMetadata().withName(jobName).withNamespace(workNamespace).withLabels(labels).endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withActiveDeadlineSeconds((long) spec.timeoutSeconds())
                .withTtlSecondsAfterFinished(spec.ttlSecondsAfterFinished())
                .withNewTemplate()
                .withNewMetadata().withLabels(labels).endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withContainers(new ContainerBuilder()
                        .withName("sandbox")
                        .withImage(spec.image())
                        .withImagePullPolicy("IfNotPresent")
                        .withCommand("/bin/sh", "-c", spec.command())
                        .withResources(resources)
                        .build())
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private static boolean isContainerRunningOrTerminated(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return false;
        }
        List<ContainerStatus> statuses = Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of());
        return statuses.stream().anyMatch(status ->
                status.getState() != null
                        && (status.getState().getRunning() != null || status.getState().getTerminated() != null));
    }

    private static boolean isContainerTerminated(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return false;
        }
        List<ContainerStatus> statuses = Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of());
        return !statuses.isEmpty() && statuses.stream().allMatch(status ->
                status.getState() != null && status.getState().getTerminated() != null);
    }

    private static int readExitCode(Pod pod) {
        return Optional.ofNullable(pod.getStatus().getContainerStatuses())
                .flatMap(list -> list.stream().findFirst())
                .map(ContainerStatus::getState)
                .map(state -> state.getTerminated().getExitCode())
                .orElse(-1);
    }
}
