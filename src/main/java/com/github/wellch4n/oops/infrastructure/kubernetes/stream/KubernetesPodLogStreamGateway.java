package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.github.wellch4n.oops.application.port.PodLogStreamGateway;
import com.github.wellch4n.oops.application.port.StreamSink;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

@Component
public class KubernetesPodLogStreamGateway implements PodLogStreamGateway {
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public AutoCloseable stream(Environment environment, String namespace, String podName, StreamSink sink) {
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();
        handle.add(client);

        PodResource podResource = client.pods().inNamespace(namespace).withName(podName);
        Pod pod = podResource.get();
        if (pod == null) {
            try {
                sink.sendText("Pod not found: " + podName);
                sink.close();
            } catch (IOException _) {
            } finally {
                handle.close();
            }
            return handle;
        }

        LogWatch logWatch;
        try {
            logWatch = podResource.tailingLines(2000).watchLog();
        } catch (RuntimeException e) {
            handle.close();
            throw e;
        }
        handle.add(logWatch);
        executorService.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                String line;
                while (handle.isOpen(sink) && (line = reader.readLine()) != null) {
                    sink.sendText(line);
                }
            } catch (IOException e) {
                if (handle.isOpen(sink)) {
                    try {
                        sink.sendText("Error reading logs: " + e.getMessage());
                    } catch (IOException _) {
                    }
                }
            } finally {
                if (sink.isOpen()) {
                    try {
                        sink.close();
                    } catch (IOException _) {
                    }
                }
                handle.close();
            }
        });
        return handle;
    }
}
