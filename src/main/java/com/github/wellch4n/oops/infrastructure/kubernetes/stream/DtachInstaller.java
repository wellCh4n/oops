package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.github.wellch4n.oops.infrastructure.kubernetes.stream.TerminalSessionSupport.ContainerProbe;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Places a statically linked {@code dtach} and a shell launcher inside the target container so a pod
 * terminal can survive a dropped WebSocket.
 *
 * <p>A {@code kubectl exec} shell dies with its stream, which is why a flaky network used to cost the
 * user their working directory and any running command. Running the shell under {@code dtach -A}
 * moves it out of the stream's lifecycle: the exec only carries an attached client, and reconnecting
 * runs the same command again to reattach to the shell that stayed behind.
 *
 * <p>Every failure mode here is non-fatal by design. Read-only filesystems, exotic architectures,
 * missing {@code cat} — all of them just leave the container without resume support, and the caller
 * opens the plain shell that OOPS has always opened.
 */
@Slf4j
@Component
public class DtachInstaller {

    private static final long PROBE_TIMEOUT_SECONDS = 15;
    private static final long UPLOAD_TIMEOUT_SECONDS = 60;

    /**
     * Prepares the scratch directory and reports what the container already has. The launcher is
     * rewritten every time — it is a few bytes, and that keeps it correct across OOPS upgrades
     * without needing a version marker.
     */
    private static final String PROBE_SCRIPT = """
            mkdir -p /tmp/.oops 2>/dev/null
            launcher=/tmp/.oops/shell.sh
            staging=$launcher.$$
            if printf '%s\\n' '#!/bin/sh' 'export TERM=xterm-256color' \\
                    'if command -v bash >/dev/null 2>&1; then exec bash; fi' \\
                    'exec /bin/sh' > "$staging" 2>/dev/null \\
                    && chmod +x "$staging" 2>/dev/null \\
                    && mv "$staging" "$launcher" 2>/dev/null; then
              printf 'launcher=yes\\n'
            else
              rm -f "$staging" 2>/dev/null
              printf 'launcher=no\\n'
            fi
            printf 'arch=%s\\n' "$(uname -m 2>/dev/null)"
            if [ -x /tmp/.oops/dtach ]; then printf 'dtach=yes\\n'; else printf 'dtach=no\\n'; fi
            """;

    /**
     * Receives the binary on stdin. Staged under a pid-suffixed name and moved into place so a
     * terminal opening concurrently can never exec a half-written file.
     */
    private static final String INSTALL_SCRIPT = """
            staging=/tmp/.oops/dtach.$$
            if cat > "$staging" && chmod +x "$staging" && mv "$staging" /tmp/.oops/dtach; then
              printf 'installed=yes\\n'
            else
              rm -f "$staging" 2>/dev/null
              printf 'installed=no\\n'
            fi
            """;

    /** Binaries are read from the JAR once and shared; they are ~50KB each and never change at runtime. */
    private final ConcurrentHashMap<String, byte[]> binaryCache = new ConcurrentHashMap<>();

    /**
     * @return true when the container is ready to run shells under dtach
     */
    public boolean ensureInstalled(KubernetesClient client, String namespace, String podName, String container) {
        try {
            ContainerProbe probe = probe(client, namespace, podName, container);
            if (!probe.launcherReady()) {
                log.debug("Terminal resume unavailable for {}/{}: launcher could not be written", namespace, podName);
                return false;
            }
            if (probe.dtachInstalled()) {
                return true;
            }
            String resource = TerminalSessionSupport.dtachResource(probe.machine());
            if (resource == null) {
                log.debug("Terminal resume unavailable for {}/{}: unsupported architecture {}", namespace, podName, probe.machine());
                return false;
            }
            byte[] binary = loadBinary(resource);
            if (binary == null) {
                return false;
            }
            return install(client, namespace, podName, container, binary);
        } catch (RuntimeException exception) {
            log.debug("Terminal resume unavailable for {}/{}", namespace, podName, exception);
            return false;
        }
    }

    private ContainerProbe probe(KubernetesClient client, String namespace, String podName, String container) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch finished = new CountDownLatch(1);

        try (ExecWatch _ = client.pods().inNamespace(namespace).withName(podName)
                .inContainer(container)
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(closingListener(finished))
                .exec("sh", "-c", PROBE_SCRIPT + "\n")) {

            if (!finished.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new ContainerProbe(false, null, false);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return new ContainerProbe(false, null, false);
        }
        return TerminalSessionSupport.parseProbe(stdout.toString(StandardCharsets.UTF_8));
    }

    private boolean install(KubernetesClient client, String namespace, String podName, String container, byte[] binary) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch finished = new CountDownLatch(1);

        // Deliberately no TTY: a pty would translate the binary's newline bytes and corrupt it.
        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                .inContainer(container)
                .redirectingInput()
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(closingListener(finished))
                .exec("sh", "-c", INSTALL_SCRIPT + "\n")) {

            OutputStream input = watch.getInput();
            input.write(binary);
            input.flush();
            input.close();

            if (!finished.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.debug("Timed out installing dtach into {}/{}", namespace, podName);
                return false;
            }
        } catch (IOException ioException) {
            log.debug("Failed to install dtach into {}/{}", namespace, podName, ioException);
            return false;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
        }

        boolean installed = stdout.toString(StandardCharsets.UTF_8).contains("installed=yes");
        if (!installed) {
            log.debug("Container {}/{} rejected the dtach install: {}", namespace, podName, stderr.toString(StandardCharsets.UTF_8));
        }
        return installed;
    }

    private byte[] loadBinary(String resource) {
        byte[] binary = binaryCache.computeIfAbsent(resource, DtachInstaller::readResource);
        return binary.length == 0 ? null : binary;
    }

    /** Returns an empty array rather than null so a missing binary is still cached and not re-read. */
    private static byte[] readResource(String resource) {
        try (InputStream stream = DtachInstaller.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                log.warn("Bundled dtach binary {} is missing; terminals will not be resumable", resource);
                return new byte[0];
            }
            return stream.readAllBytes();
        } catch (IOException ioException) {
            log.warn("Failed to read bundled dtach binary {}", resource, ioException);
            return new byte[0];
        }
    }

    private static ExecListener closingListener(CountDownLatch finished) {
        return new ExecListener() {
            @Override
            public void onClose(int code, String reason) {
                finished.countDown();
            }

            @Override
            public void onFailure(Throwable throwable, Response response) {
                finished.countDown();
            }
        };
    }
}
