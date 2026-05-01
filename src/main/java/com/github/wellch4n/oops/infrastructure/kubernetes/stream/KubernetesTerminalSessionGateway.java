package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.github.wellch4n.oops.application.port.StreamSink;
import com.github.wellch4n.oops.application.port.TerminalSessionGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.IOException;
import java.io.OutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KubernetesTerminalSessionGateway implements TerminalSessionGateway {

    @Override
    public TerminalSession open(Environment environment, String namespace, String podName, String container, StreamSink sink) {
        KubernetesClient client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer());
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        handle.add(client);

        ExecWatch watch;
        try {
            watch = client.pods().inNamespace(namespace).withName(podName)
                    .inContainer(container)
                    .redirectingInput()
                    .writingOutput(new StreamSinkOutputStream(sink))
                    .writingError(new StreamSinkOutputStream(sink))
                    .withTTY()
                    .usingListener(new ExecListener() {
                        @Override
                        public void onClose(int code, String reason) {
                            closeSink(sink);
                        }

                        @Override
                        public void onFailure(Throwable t, Response response) {
                            log.warn("Terminal session failed for pod {}/{}: {}", namespace, podName, t.getMessage());
                            closeSinkWithError(sink);
                        }
                    })
                    .exec("sh", "-c", "export TERM=xterm-256color; exec /bin/sh");
        } catch (RuntimeException e) {
            handle.close();
            throw e;
        }

        handle.add(watch);
        OutputStream stdin = watch.getInput();
        return new KubernetesTerminalSession(stdin, handle);
    }

    private static void closeSink(StreamSink sink) {
        try {
            sink.close();
        } catch (IOException _) {
        }
    }

    private static void closeSinkWithError(StreamSink sink) {
        try {
            sink.closeWithError();
        } catch (IOException _) {
        }
    }

    private static final class KubernetesTerminalSession implements TerminalSession {
        private final OutputStream stdin;
        private final KubernetesStreamHandle handle;

        private KubernetesTerminalSession(OutputStream stdin, KubernetesStreamHandle handle) {
            this.stdin = stdin;
            this.handle = handle;
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            stdin.write(bytes);
            stdin.flush();
        }

        @Override
        public void close() {
            try {
                stdin.close();
            } catch (IOException _) {
            }
            handle.close();
        }
    }
}
