package com.github.wellch4n.oops.infrastructure.metrics;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClientPool;
import com.github.wellch4n.oops.shared.exception.BizException;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reaches the monitoring backend through the Kubernetes API server's service proxy.
 *
 * <p>Prometheus and VictoriaMetrics are normally ClusterIP Services, unreachable from outside the cluster. Proxying
 * through the API server means OOPS needs no extra network path and no second credential — the environment's existing
 * token is what authorises the call. The cost is a {@code services/proxy} permission on that token.
 */
@Slf4j
@Component
class ApiServerProxyPrometheusTransport implements PrometheusQueryTransport {

    private final KubernetesClientPool clientPool;

    ApiServerProxyPrometheusTransport(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public String queryRange(Environment environment,
                             MetricsHistoryProperties.Backend backend,
                             String query,
                             long startSeconds,
                             long endSeconds,
                             int stepSeconds) {
        KubernetesClient client = clientPool.get(environment.getKubernetesApiServer());

        String url = client.getMasterUrl().toString().replaceAll("/+$", "")
                + "/api/v1/namespaces/" + backend.getNamespace()
                + "/services/" + backend.getServiceName() + ":" + backend.getPort()
                + "/proxy/api/v1/query_range"
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=" + startSeconds
                + "&end=" + endSeconds
                + "&step=" + stepSeconds;

        try {
            String body = client.raw(url);
            if (body == null) {
                // The proxy answers 404 for a Service that does not exist, which Fabric8 surfaces as a null body on
                // some paths and as an exception on others. Either way it means this cluster simply has no
                // monitoring installed where the convention says it should be — not that something is broken.
                log.info("No monitoring backend at {} in environment {}", backend.describe(), environment.getName());
                throw new BizException(MonitoringErrors.NOT_AVAILABLE);
            }
            return body;
        } catch (BizException exception) {
            throw exception;
        } catch (KubernetesClientException exception) {
            if (exception.getCode() == 404) {
                log.info("No monitoring backend at {} in environment {}", backend.describe(), environment.getName());
                throw new BizException(MonitoringErrors.NOT_AVAILABLE);
            }
            throw reachFailure(environment, backend, exception);
        } catch (Exception exception) {
            throw reachFailure(environment, backend, exception);
        }
    }

    private BizException reachFailure(Environment environment,
                                      MetricsHistoryProperties.Backend backend,
                                      Exception exception) {
        log.warn("Monitoring query failed for environment {} at {}: {}",
                environment.getName(), backend.describe(), exception.getMessage());
        return new BizException("Cannot reach the monitoring backend at "
                + backend.describe() + ": " + exception.getMessage(), exception);
    }
}
