package com.github.wellch4n.oops.informer;

import com.github.wellch4n.oops.data.Environment;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Per-environment registry of Fabric8 SharedInformers.
 * Currently hosts only a Job informer per environment's workNamespace.
 */
@Slf4j
@Component
public class KubernetesInformerRegistry {

    private static final long RESYNC_PERIOD_MS = 60_000L;

    private final PipelineJobEventHandler jobEventHandler;
    private final Map<String, EnvInformers> registry = new ConcurrentHashMap<>();

    public KubernetesInformerRegistry(PipelineJobEventHandler jobEventHandler) {
        this.jobEventHandler = jobEventHandler;
    }

    public synchronized void register(Environment environment) {
        if (environment == null || environment.getId() == null) {
            return;
        }
        if (registry.containsKey(environment.getId())) {
            return;
        }
        try {
            KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();
            SharedIndexInformer<Job> jobInformer = client.batch().v1().jobs()
                    .inNamespace(environment.getWorkNamespace())
                    .inform(new EnvScopedJobHandler(environment, jobEventHandler), RESYNC_PERIOD_MS);
            Lister<Job> jobLister = new Lister<>(jobInformer.getIndexer(), environment.getWorkNamespace());
            registry.put(environment.getId(), new EnvInformers(environment, client, jobInformer, jobLister));
            log.info("Informer registered for env={} ns={}", environment.getName(), environment.getWorkNamespace());
        } catch (Exception e) {
            log.error("Failed to register informer for env={}: {}", environment.getName(), e.getMessage(), e);
        }
    }

    public synchronized void unregister(String environmentId) {
        EnvInformers removed = registry.remove(environmentId);
        if (removed != null) {
            removed.close();
            log.info("Informer unregistered for envId={}", environmentId);
        }
    }

    public synchronized void refresh(Environment environment) {
        if (environment == null || environment.getId() == null) {
            return;
        }
        unregister(environment.getId());
        register(environment);
    }

    public Job getJob(String environmentId, String jobName) {
        EnvInformers env = registry.get(environmentId);
        if (env == null) {
            return null;
        }
        return env.jobLister.get(jobName);
    }

    public boolean hasEnvironment(String environmentId) {
        return registry.containsKey(environmentId);
    }

    @PreDestroy
    public void shutdown() {
        registry.values().forEach(EnvInformers::close);
        registry.clear();
    }

    private record EnvInformers(Environment environment,
                                KubernetesClient client,
                                SharedIndexInformer<Job> jobInformer,
                                Lister<Job> jobLister) {
        void close() {
            try {
                jobInformer.stop();
            } catch (Exception ignored) {
            }
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Wraps the shared handler so each informer event carries the owning Environment.
     */
    private record EnvScopedJobHandler(Environment environment, PipelineJobEventHandler delegate)
            implements io.fabric8.kubernetes.client.informers.ResourceEventHandler<Job> {
        @Override
        public void onAdd(Job job) {
            delegate.handle(environment, job);
        }

        @Override
        public void onUpdate(Job oldJob, Job newJob) {
            delegate.handle(environment, newJob);
        }

        @Override
        public void onDelete(Job job, boolean deletedFinalStateUnknown) {
            // Pipeline state is not affected by Job deletion (GC, manual cleanup).
        }
    }
}
