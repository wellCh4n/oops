package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.dto.ApplicationResourceView;
import com.github.wellch4n.oops.application.port.ApplicationExpertConfigGateway;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.application.ApplicationPriority;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.IngressRoute;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.scheduling.v1.PriorityClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KubernetesApplicationExpertConfigGateway implements ApplicationExpertConfigGateway {

    private static final String DEFAULT_SERVICE_ACCOUNT = "default";
    private static final String APP_NAME_LABEL = "oops.app.name";

    private final KubernetesClientPool clientPool;

    public KubernetesApplicationExpertConfigGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public void applyExpertConfig(Environment environment,
                                  String namespace,
                                  String applicationName,
                                  ApplicationExpertConfig.EnvironmentConfig expertConfig) {
        if (expertConfig == null) {
            return;
        }
        var client = clientPool.get(environment.getKubernetesApiServer());
        var statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(applicationName)
                .get();
        if (statefulSet == null) {
            return;
        }
        String serviceAccountName = StringUtils.isNotBlank(expertConfig.getServiceAccountName())
                ? expertConfig.getServiceAccountName()
                : DEFAULT_SERVICE_ACCOUNT;
        // Null clears any existing PriorityClass, letting the pod fall back to the cluster default
        // (normal tier). Editing the template triggers a rolling update, same as the service account.
        ApplicationPriority priority = ApplicationPriority.fromValue(expertConfig.getPriority());
        ensurePriorityClass(client, priority);
        String priorityClassName = priority.priorityClassName();
        client.apps().statefulSets().inNamespace(namespace).withName(applicationName)
                .edit(target -> {
                    target.getSpec().getTemplate().getSpec().setServiceAccountName(serviceAccountName);
                    target.getSpec().getTemplate().getSpec()
                            .setPriorityClassName(StringUtils.isNotBlank(priorityClassName) ? priorityClassName : null);
                    return target;
                });
    }

    /**
     * Create the cluster-scoped PriorityClass backing the tier if it requires one and is absent.
     * Created only when missing and never overwritten — {@code value} is immutable and the object is
     * a cluster-wide singleton an administrator may have pre-created with a deliberate value.
     */
    private void ensurePriorityClass(KubernetesClient client, ApplicationPriority priority) {
        String name = priority.priorityClassName();
        if (name == null) {
            return;
        }
        if (client.scheduling().v1().priorityClasses().withName(name).get() != null) {
            return;
        }
        log.info("Creating PriorityClass {} (value={})", name, priority.defaultValue());
        try {
            client.scheduling().v1().priorityClasses().resource(new PriorityClassBuilder()
                            .withNewMetadata().withName(name).endMetadata()
                            .withValue(priority.defaultValue())
                            .withGlobalDefault(false)
                            .withDescription("Managed by OOPS — " + priority.name().toLowerCase() + " priority applications")
                            .build())
                    .create();
        } catch (KubernetesClientException exception) {
            // A concurrent deploy may have created it between our get and create. A pre-existing
            // PriorityClass is exactly the desired state, so swallow the conflict and rethrow anything else.
            if (exception.getCode() != HttpURLConnection.HTTP_CONFLICT) {
                throw exception;
            }
            log.debug("PriorityClass {} was created concurrently, leaving the existing object as-is", name);
        }
    }

    @Override
    public List<ApplicationResourceView> getApplicationResources(Environment environment,
                                                                 String namespace,
                                                                 String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        List<ApplicationResourceView> resources = new ArrayList<>();

        var statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(applicationName)
                .get();
        if (statefulSet != null) {
            resources.add(toView(statefulSet));
        }

        client.services()
                .inNamespace(namespace)
                .withLabel(APP_NAME_LABEL, applicationName)
                .list().getItems()
                .forEach(service -> resources.add(toView(service)));

        appendIngressRoutes(client, namespace, applicationName, resources);

        return resources;
    }

    private void appendIngressRoutes(KubernetesClient client,
                                     String namespace,
                                     String applicationName,
                                     List<ApplicationResourceView> resources) {
        try {
            client.resources(IngressRoute.class)
                    .inNamespace(namespace)
                    .withLabel(APP_NAME_LABEL, applicationName)
                    .list().getItems()
                    .forEach(ingressRoute -> resources.add(toView(ingressRoute)));
        } catch (Exception e) {
            // Traefik IngressRoute CRD may be absent on this cluster — skip gracefully.
            log.warn("Failed to read IngressRoutes for {}/{}: {}", namespace, applicationName, e.getMessage());
        }
    }

    private ApplicationResourceView toView(HasMetadata resource) {
        // Drop noisy server-managed metadata so the rendered manifest stays readable.
        if (resource.getMetadata() != null) {
            resource.getMetadata().setManagedFields(null);
        }
        String name = resource.getMetadata() != null ? resource.getMetadata().getName() : "";
        return new ApplicationResourceView(resource.getKind(), name, Serialization.asYaml(resource));
    }
}
