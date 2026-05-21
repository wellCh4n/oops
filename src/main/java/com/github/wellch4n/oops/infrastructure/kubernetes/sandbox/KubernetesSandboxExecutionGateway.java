package com.github.wellch4n.oops.infrastructure.kubernetes.sandbox;

import com.github.wellch4n.oops.application.port.SandboxExecutionGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.sandbox.BuiltinSandboxRuntime;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstance;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstanceStatus;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClientPool;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.shared.util.NanoIdUtils;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class KubernetesSandboxExecutionGateway implements SandboxExecutionGateway {

    private static final String LABEL_TYPE = "oops.type";
    private static final String LABEL_TYPE_VALUE = "sandbox";
    private static final String LABEL_SANDBOX_ID = "oops.sandbox.id";
    private static final String LABEL_KIND = "oops.sandbox.kind";
    private static final String LABEL_KIND_EPHEMERAL = "ephemeral";
    private static final String LABEL_KIND_PERSISTENT = "persistent";
    private static final String LABEL_CREATED_BY = "oops.sandbox.created-by";
    private static final String LABEL_IMAGE = "oops.sandbox.image";

    private static final String ANNOTATION_NAME = "oops.sandbox.name";
    private static final String ANNOTATION_IMAGE = "oops.sandbox.image";
    private static final String ANNOTATION_CPU_REQUEST = "oops.sandbox.cpu-request";
    private static final String ANNOTATION_CPU_LIMIT = "oops.sandbox.cpu-limit";
    private static final String ANNOTATION_MEMORY_REQUEST = "oops.sandbox.memory-request";
    private static final String ANNOTATION_MEMORY_LIMIT = "oops.sandbox.memory-limit";

    private static final String SANDBOX_NAME_PREFIX = "oops-sandbox-";
    private static final String CONTAINER_NAME = "sandbox";
    private static final String PERSISTENT_KEEPALIVE_COMMAND = "trap : TERM INT; sleep infinity & wait";
    private static final String BIN_SH = "/bin/sh";
    private static final String RESOURCE_CPU = "cpu";
    private static final String RESOURCE_MEMORY = "memory";
    private static final int MAX_LABEL_VALUE_LENGTH = 63;
    private static final Pattern LABEL_VALUE_INVALID_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern LABEL_VALUE_EDGE_TRIM = Pattern.compile("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$");

    private final KubernetesClientPool clientPool;

    public KubernetesSandboxExecutionGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @PostConstruct
    void silenceFabric8ShutdownNoise() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                "io.fabric8.kubernetes.client.informers.impl.cache.Reflector"))
                .setLevel(ch.qos.logback.classic.Level.OFF);
    }

    @Override
    public SseEmitter stream(Environment environment, SandboxJobSpec spec) {
        SseEmitter emitter = new SseEmitter((spec.timeoutSeconds() + 30L) * 1000L);
        String sandboxId = NanoIdUtils.generate();
        String jobName = SANDBOX_NAME_PREFIX + sandboxId;
        String workNamespace = environment.getWorkNamespace();

        Thread.startVirtualThread(() -> {
            try (KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer())) {
                String podName = launchJobAndWaitForPod(client, spec, jobName, sandboxId, workNamespace);
                try (LogWatch logWatch = client.pods().inNamespace(workNamespace).withName(podName).watchLog()) {
                    closeLogWatchOnTermination(client, workNamespace, podName, logWatch, spec.timeoutSeconds());
                    BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        emitter.send(SseEmitter.event().name("log").data(line));
                    }
                } catch (Exception _) {
                }
                Pod terminated = client.pods().inNamespace(workNamespace).withName(podName)
                        .waitUntilCondition(KubernetesSandboxExecutionGateway::isContainerTerminated, 1, TimeUnit.MINUTES);
                emitter.send(SseEmitter.event().name("exit").data(readExitCode(terminated)));
                emitter.complete();
            } catch (Exception exception) {
                log.warn("Sandbox stream {} ended abnormally: {}", jobName, exception.getMessage());
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    @Override
    public SandboxExecutionResult execute(Environment environment, SandboxJobSpec spec) {
        String sandboxId = NanoIdUtils.generate();
        String jobName = SANDBOX_NAME_PREFIX + sandboxId;
        String workNamespace = environment.getWorkNamespace();

        try (KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer())) {
            String podName = launchJobAndWaitForPod(client, spec, jobName, sandboxId, workNamespace);
            StringBuilder output = new StringBuilder();
            try (LogWatch logWatch = client.pods().inNamespace(workNamespace).withName(podName).watchLog()) {
                closeLogWatchOnTermination(client, workNamespace, podName, logWatch, spec.timeoutSeconds());
                BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (Exception _) {
            }
            Pod terminated = client.pods().inNamespace(workNamespace).withName(podName)
                    .waitUntilCondition(KubernetesSandboxExecutionGateway::isContainerTerminated, 1, TimeUnit.MINUTES);
            return new SandboxExecutionResult(readExitCode(terminated), output.toString());
        } catch (Exception exception) {
            log.warn("Sandbox execution {} ended abnormally: {}", jobName, exception.getMessage());
            throw new RuntimeException("Sandbox execution failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public SandboxInstance createPersistent(Environment environment, PersistentSandboxSpec spec) {
        return BuiltinSandboxRuntime.from(spec.image())
                .map(builtin -> createBuiltinPersistent(environment, spec, builtin))
                .orElseGet(() -> createCustomPersistent(environment, spec));
    }

    private SandboxInstance createBuiltinPersistent(Environment environment, PersistentSandboxSpec spec,
                                                    BuiltinSandboxRuntime builtin) {
        String workNamespace = environment.getWorkNamespace();
        String statefulSetName = SANDBOX_NAME_PREFIX + spec.sandboxId();
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());

        Map<String, String> labels = buildPersistentLabels(spec, builtin.getKey());
        Map<String, String> annotations = buildPersistentAnnotations(spec);
        StatefulSet statefulSet = switch (builtin) {
            case ALPINE_MATE -> AlpineMateTemplate.buildStatefulSet(spec, statefulSetName, workNamespace, labels, annotations, toEnvVars(spec.env()));
        };
        Service service = switch (builtin) {
            case ALPINE_MATE -> AlpineMateTemplate.buildService(statefulSetName, workNamespace, spec.sandboxId());
        };

        StatefulSet created;
        try {
            created = client.apps().statefulSets().inNamespace(workNamespace).resource(statefulSet).create();
        } catch (Exception exception) {
            log.warn("Failed to create builtin sandbox {}: {}", statefulSetName, exception.getMessage());
            throw new BizException("Failed to create sandbox: " + exception.getMessage());
        }

        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion("apps/v1")
                .withKind("StatefulSet")
                .withName(statefulSetName)
                .withUid(created.getMetadata().getUid())
                .withBlockOwnerDeletion(true)
                .build();
        service.getMetadata().setOwnerReferences(List.of(ownerReference));
        try {
            client.services().inNamespace(workNamespace).resource(service).create();
        } catch (Exception exception) {
            log.warn("Failed to create builtin sandbox service {}: {}", statefulSetName, exception.getMessage());
            throw new BizException("Failed to create sandbox: " + exception.getMessage());
        }

        return toSandboxInstance(environment, created, findPod(client, workNamespace, spec.sandboxId()));
    }

    private Map<String, String> buildPersistentLabels(PersistentSandboxSpec spec, String imageLabel) {
        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_TYPE, LABEL_TYPE_VALUE);
        labels.put(LABEL_KIND, LABEL_KIND_PERSISTENT);
        labels.put(LABEL_SANDBOX_ID, spec.sandboxId());
        labels.put(LABEL_IMAGE, sanitizeLabelValue(imageLabel));
        if (spec.createdByUserId() != null && !spec.createdByUserId().isBlank()) {
            labels.put(LABEL_CREATED_BY, spec.createdByUserId());
        }
        return labels;
    }

    private Map<String, String> buildPersistentAnnotations(PersistentSandboxSpec spec) {
        Map<String, String> annotations = new HashMap<>();
        annotations.put(ANNOTATION_NAME, spec.name());
        annotations.put(ANNOTATION_IMAGE, spec.image());
        if (spec.cpuRequest() != null) {
            annotations.put(ANNOTATION_CPU_REQUEST, spec.cpuRequest());
        }
        if (spec.cpuLimit() != null) {
            annotations.put(ANNOTATION_CPU_LIMIT, spec.cpuLimit());
        }
        if (spec.memoryRequest() != null) {
            annotations.put(ANNOTATION_MEMORY_REQUEST, spec.memoryRequest());
        }
        if (spec.memoryLimit() != null) {
            annotations.put(ANNOTATION_MEMORY_LIMIT, spec.memoryLimit());
        }
        return annotations;
    }

    private SandboxInstance createCustomPersistent(Environment environment, PersistentSandboxSpec spec) {
        String workNamespace = environment.getWorkNamespace();
        String statefulSetName = SANDBOX_NAME_PREFIX + spec.sandboxId();
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());

        StatefulSet statefulSet = buildPersistentStatefulSet(spec, statefulSetName, workNamespace);
        StatefulSet created;
        try {
            created = client.apps().statefulSets()
                    .inNamespace(workNamespace)
                    .resource(statefulSet)
                    .create();
        } catch (Exception exception) {
            log.warn("Failed to create persistent sandbox {}: {}", statefulSetName, exception.getMessage());
            throw new BizException("Failed to create sandbox: " + exception.getMessage());
        }
        return toSandboxInstance(environment, created, findPod(client, workNamespace, spec.sandboxId()));
    }

    @Override
    public List<SandboxInstance> listPersistent(Environment environment, String createdByUserId, String image) {
        String workNamespace = environment.getWorkNamespace();
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());
        Map<String, String> selector = new HashMap<>();
        selector.put(LABEL_TYPE, LABEL_TYPE_VALUE);
        selector.put(LABEL_KIND, LABEL_KIND_PERSISTENT);
        if (createdByUserId != null && !createdByUserId.isBlank()) {
            selector.put(LABEL_CREATED_BY, createdByUserId);
        }
        if (image != null && !image.isBlank()) {
            selector.put(LABEL_IMAGE, sanitizeLabelValue(image));
        }
        List<StatefulSet> statefulSets = client.apps().statefulSets()
                .inNamespace(workNamespace)
                .withLabels(selector)
                .list()
                .getItems();
        return statefulSets.stream()
                .map(statefulSet -> {
                    String sandboxId = statefulSet.getMetadata().getLabels().get(LABEL_SANDBOX_ID);
                    Pod pod = sandboxId != null ? findPod(client, workNamespace, sandboxId) : null;
                    return toSandboxInstance(environment, statefulSet, pod);
                })
                .sorted(Comparator.comparing(SandboxInstance::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public Optional<SandboxInstance> findPersistent(Environment environment, String sandboxId) {
        String workNamespace = environment.getWorkNamespace();
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());
        StatefulSet statefulSet = client.apps().statefulSets()
                .inNamespace(workNamespace)
                .withName(SANDBOX_NAME_PREFIX + sandboxId)
                .get();
        if (statefulSet == null) {
            return Optional.empty();
        }
        String kind = statefulSet.getMetadata().getLabels() != null
                ? statefulSet.getMetadata().getLabels().get(LABEL_KIND)
                : null;
        if (!LABEL_KIND_PERSISTENT.equals(kind)) {
            return Optional.empty();
        }
        Pod pod = findPod(client, workNamespace, sandboxId);
        return Optional.of(toSandboxInstance(environment, statefulSet, pod));
    }

    @Override
    public void deletePersistent(Environment environment, String sandboxId) {
        String workNamespace = environment.getWorkNamespace();
        String sandboxName = SANDBOX_NAME_PREFIX + sandboxId;
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());
        client.apps().statefulSets().inNamespace(workNamespace).withName(sandboxName).delete();
    }

    @Override
    public SandboxExecutionResult execInstance(Environment environment, String sandboxId, String command, int timeoutSeconds) {
        String workNamespace = environment.getWorkNamespace();
        String podName = SANDBOX_NAME_PREFIX + sandboxId + "-0";
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Integer> exitCode = new AtomicReference<>(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ExecWatch _ = client.pods().inNamespace(workNamespace).withName(podName)
                .inContainer(CONTAINER_NAME)
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        exitCode.set(code);
                        finished.countDown();
                    }

                    @Override
                    public void onFailure(Throwable throwable, Response response) {
                        log.warn("Sandbox exec failed for {}/{}: {}", workNamespace, podName, throwable.getMessage());
                        failure.set(throwable);
                        finished.countDown();
                    }
                })
                .exec(BIN_SH, "-c", command)) {

            if (!finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new BizException("Sandbox exec timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BizException("Sandbox exec interrupted");
        }

        if (failure.get() != null) {
            throw new BizException("Sandbox exec failed: " + failure.get().getMessage());
        }
        String combined = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
        return new SandboxExecutionResult(exitCode.get(), combined);
    }

    @Override
    public SseEmitter streamExecInstance(Environment environment, String sandboxId, String command, int timeoutSeconds) {
        SseEmitter emitter = new SseEmitter((timeoutSeconds + 30L) * 1000L);
        String workNamespace = environment.getWorkNamespace();
        String podName = SANDBOX_NAME_PREFIX + sandboxId + "-0";
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());

        SseLineOutputStream stdoutStream = new SseLineOutputStream(emitter, "log");
        SseLineOutputStream stderrStream = new SseLineOutputStream(emitter, "log");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Integer> exitCode = new AtomicReference<>(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread.startVirtualThread(() -> {
            try (ExecWatch _ = client.pods().inNamespace(workNamespace).withName(podName)
                    .inContainer(CONTAINER_NAME)
                    .writingOutput(stdoutStream)
                    .writingError(stderrStream)
                    .usingListener(new ExecListener() {
                        @Override
                        public void onClose(int code, String reason) {
                            exitCode.set(code);
                            finished.countDown();
                        }

                        @Override
                        public void onFailure(Throwable throwable, Response response) {
                            log.warn("Sandbox stream exec failed for {}/{}: {}", workNamespace, podName, throwable.getMessage());
                            failure.set(throwable);
                            finished.countDown();
                        }
                    })
                    .exec(BIN_SH, "-c", command)) {

                if (!finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    emitter.completeWithError(new BizException("Sandbox exec timed out"));
                    return;
                }
                stdoutStream.flushPending();
                stderrStream.flushPending();
                if (failure.get() != null) {
                    emitter.completeWithError(failure.get());
                    return;
                }
                emitter.send(SseEmitter.event().name("exit").data(exitCode.get()));
                emitter.complete();
            } catch (Exception exception) {
                log.warn("Sandbox stream exec {} ended abnormally: {}", podName, exception.getMessage());
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private String launchJobAndWaitForPod(KubernetesClient client, SandboxJobSpec spec,
                                          String jobName, String sandboxId, String workNamespace) {
        client.batch().v1().jobs().inNamespace(workNamespace)
                .resource(buildJob(spec, jobName, sandboxId, workNamespace)).create();
        Pod pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                .waitUntilCondition(Objects::nonNull, 2, TimeUnit.MINUTES);
        String podName = pod.getMetadata().getName();
        client.pods().inNamespace(workNamespace).withName(podName)
                .waitUntilCondition(KubernetesSandboxExecutionGateway::isContainerRunningOrTerminated, 2, TimeUnit.MINUTES);
        return podName;
    }

    private void closeLogWatchOnTermination(KubernetesClient client, String workNamespace,
                                            String podName, LogWatch logWatch, int timeoutSeconds) {
        // Force EOF when the container terminates: K8s sometimes leaves /log?follow=true
        // open after the pod exits, leaving readLine() blocked forever.
        Thread.startVirtualThread(() -> {
            try {
                client.pods().inNamespace(workNamespace).withName(podName)
                        .waitUntilCondition(KubernetesSandboxExecutionGateway::isContainerTerminated, timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception _) {
            } finally {
                logWatch.close();
            }
        });
    }

    private Job buildJob(SandboxJobSpec spec, String jobName, String sandboxId, String workNamespace) {
        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_TYPE, LABEL_TYPE_VALUE);
        labels.put(LABEL_KIND, LABEL_KIND_EPHEMERAL);
        labels.put(LABEL_SANDBOX_ID, sandboxId);
        if (spec.createdByUserId() != null && !spec.createdByUserId().isBlank()) {
            labels.put(LABEL_CREATED_BY, spec.createdByUserId());
        }

        ResourceRequirements resources = new ResourceRequirements();
        resources.setRequests(Map.of(RESOURCE_CPU, new Quantity(spec.cpuRequest()), RESOURCE_MEMORY, new Quantity(spec.memoryRequest())));
        resources.setLimits(Map.of(RESOURCE_CPU, new Quantity(spec.cpuLimit()), RESOURCE_MEMORY, new Quantity(spec.memoryLimit())));

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
                        .withName(CONTAINER_NAME)
                        .withImage(spec.image())
                        .withImagePullPolicy("IfNotPresent")
                        .withCommand(BIN_SH, "-c", spec.command())
                        .withEnv(toEnvVars(spec.env()))
                        .withResources(resources)
                        .withSecurityContext(new SecurityContextBuilder().withPrivileged(true).build())
                        .build())
                .addNewImagePullSecret("dockerhub")
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private StatefulSet buildPersistentStatefulSet(PersistentSandboxSpec spec, String statefulSetName, String workNamespace) {
        Map<String, String> labels = buildPersistentLabels(spec, spec.image());
        Map<String, String> annotations = buildPersistentAnnotations(spec);

        ResourceRequirementsBuilder resourcesBuilder = new ResourceRequirementsBuilder();
        if (isNotBlank(spec.cpuRequest())) {
            resourcesBuilder.addToRequests(RESOURCE_CPU, new Quantity(spec.cpuRequest()));
        }
        if (isNotBlank(spec.memoryRequest())) {
            resourcesBuilder.addToRequests(RESOURCE_MEMORY, new Quantity(withMemoryUnit(spec.memoryRequest())));
        }
        if (isNotBlank(spec.cpuLimit())) {
            resourcesBuilder.addToLimits(RESOURCE_CPU, new Quantity(spec.cpuLimit()));
        }
        if (isNotBlank(spec.memoryLimit())) {
            resourcesBuilder.addToLimits(RESOURCE_MEMORY, new Quantity(withMemoryUnit(spec.memoryLimit())));
        }
        ResourceRequirements resources = resourcesBuilder.build();

        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withName(CONTAINER_NAME)
                .withImage(spec.image())
                .withImagePullPolicy("IfNotPresent")
                .withEnv(toEnvVars(spec.env()))
                .withResources(resources)
                .withSecurityContext(new SecurityContextBuilder().withPrivileged(true).build());
        if (spec.useDefaultKeepalive()) {
            containerBuilder.withCommand(BIN_SH, "-c", PERSISTENT_KEEPALIVE_COMMAND);
        } else {
            containerBuilder.withStdin(true).withTty(true);
        }

        return new StatefulSetBuilder()
                .withApiVersion("apps/v1").withKind("StatefulSet")
                .withNewMetadata()
                    .withName(statefulSetName)
                    .withNamespace(workNamespace)
                    .withLabels(labels)
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withServiceName(statefulSetName)
                    .withReplicas(1)
                    .withNewSelector()
                        .addToMatchLabels(labels)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                            .withAnnotations(annotations)
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Always")
                            .withContainers(containerBuilder.build())
                            .addNewImagePullSecret("dockerhub")
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String sanitizeLabelValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String replaced = LABEL_VALUE_INVALID_CHARS.matcher(value).replaceAll("_");
        if (replaced.length() > MAX_LABEL_VALUE_LENGTH) {
            replaced = replaced.substring(0, MAX_LABEL_VALUE_LENGTH);
        }
        return LABEL_VALUE_EDGE_TRIM.matcher(replaced).replaceAll("");
    }

    private static List<EnvVar> toEnvVars(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return List.of();
        }
        return env.entrySet().stream()
                .map(entry -> new EnvVarBuilder().withName(entry.getKey()).withValue(entry.getValue()).build())
                .toList();
    }

    private static String withMemoryUnit(String value) {
        return value + "Mi";
    }

    private Pod findPod(KubernetesClient client, String workNamespace, String sandboxId) {
        String podName = SANDBOX_NAME_PREFIX + sandboxId + "-0";
        return client.pods().inNamespace(workNamespace).withName(podName).get();
    }

    private SandboxInstance toSandboxInstance(Environment environment, StatefulSet statefulSet, Pod pod) {
        ObjectMeta metadata = statefulSet.getMetadata();
        Map<String, String> labels = Optional.ofNullable(metadata.getLabels()).orElse(Map.of());
        Map<String, String> annotations = Optional.ofNullable(metadata.getAnnotations()).orElse(Map.of());
        return SandboxInstance.builder()
                .id(labels.get(LABEL_SANDBOX_ID))
                .name(annotations.get(ANNOTATION_NAME))
                .environment(environment.getName())
                .image(labels.get(LABEL_IMAGE))
                .image(annotations.get(ANNOTATION_IMAGE))
                .status(deriveStatus(statefulSet, pod))
                .createdBy(labels.get(LABEL_CREATED_BY))
                .createdAt(parseCreatedAt(metadata.getCreationTimestamp()))
                .cpuRequest(annotations.get(ANNOTATION_CPU_REQUEST))
                .cpuLimit(annotations.get(ANNOTATION_CPU_LIMIT))
                .memoryRequest(annotations.get(ANNOTATION_MEMORY_REQUEST))
                .memoryLimit(annotations.get(ANNOTATION_MEMORY_LIMIT))
                .build();
    }

    private static Instant parseCreatedAt(String creationTimestamp) {
        if (creationTimestamp == null || creationTimestamp.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(creationTimestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SandboxInstanceStatus deriveStatus(StatefulSet statefulSet, Pod pod) {
        if (statefulSet.getMetadata().getDeletionTimestamp() != null) {
            return SandboxInstanceStatus.TERMINATING;
        }
        if (pod == null) {
            return SandboxInstanceStatus.PENDING;
        }
        List<ContainerStatus> statuses = Optional.ofNullable(pod.getStatus())
                .map(status -> status.getContainerStatuses())
                .orElse(List.of());
        if (statuses.isEmpty()) {
            return SandboxInstanceStatus.PENDING;
        }
        boolean allReady = statuses.stream().allMatch(status -> Boolean.TRUE.equals(status.getReady()));
        if (allReady) {
            return SandboxInstanceStatus.RUNNING;
        }
        boolean anyFailed = statuses.stream().anyMatch(status -> {
            if (status.getState() == null || status.getState().getWaiting() == null) {
                return false;
            }
            String reason = status.getState().getWaiting().getReason();
            return "CrashLoopBackOff".equals(reason)
                    || "ImagePullBackOff".equals(reason)
                    || "ErrImagePull".equals(reason)
                    || "CreateContainerConfigError".equals(reason);
        });
        return anyFailed ? SandboxInstanceStatus.FAILED : SandboxInstanceStatus.PENDING;
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

    private static final class SseLineOutputStream extends OutputStream {
        private final SseEmitter emitter;
        private final String eventName;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        SseLineOutputStream(SseEmitter emitter, String eventName) {
            this.emitter = emitter;
            this.eventName = eventName;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emitLine();
            } else {
                buffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            for (int index = 0; index < length; index++) {
                write(data[offset + index]);
            }
        }

        synchronized void flushPending() {
            if (buffer.size() > 0) {
                emitLine();
            }
        }

        private void emitLine() {
            String line = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            try {
                emitter.send(SseEmitter.event().name(eventName).data(line));
            } catch (Exception ignored) {
            }
        }
    }
}
