package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.github.wellch4n.oops.application.port.StreamSink;
import com.github.wellch4n.oops.application.port.TerminalSessionGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.TerminalProperties;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClientPool;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KubernetesTerminalSessionGateway implements TerminalSessionGateway {

    private static final long TERMINATE_TIMEOUT_SECONDS = 15;

    /**
     * Runs the user's shell under dtach when the container supports it, so the shell outlives this
     * exec stream and a reconnect can reattach to it. {@code -A} creates the session or attaches to
     * an existing one, which makes the first connection and every reconnect the same command.
     * {@code -r winch} nudges full-screen programs to redraw on reattach, and {@code -E} drops
     * dtach's own detach key so it can never swallow a keystroke meant for the shell.
     *
     * <p>The trailing lines are the fallback OOPS has always used, reached whenever dtach is absent
     * or refuses to start, so a terminal never fails just because it cannot be made resumable.
     * Reaching them depends on the guard actually running the binary rather than testing {@code -x}:
     * once this script reaches {@code exec}, a binary that will not load takes the shell down with it
     * — the exec replaces the shell, so there is nothing left to fall back to, and the browser
     * reconnects into the same failure indefinitely. The check is cheap and re-run per connect so a
     * verdict that went stale since the install cannot strand the terminal either.
     */
    private static final String TERMINAL_SCRIPT = """
            socket=$1
            if [ -n "$socket" ] && [ -x /tmp/.oops/shell.sh ] && %s; then
              exec /tmp/.oops/dtach -A "$socket" -E -r winch /tmp/.oops/shell.sh
            fi
            export TERM=xterm-256color
            if command -v bash >/dev/null 2>&1; then exec bash; fi
            exec /bin/sh
            """.formatted(TerminalSessionSupport.DTACH_RUNS_CHECK);

    /**
     * Kills the dtach master holding a session. The socket path is unique per session and appears in
     * the master's argv, so scanning {@code /proc} identifies it without needing {@code pkill}, which
     * slim images lack. The loop skips its own pid — the path appears in this script's argv too, so a
     * naive scan would kill the killer before it finished.
     */
    private static final String TERMINATE_SCRIPT = """
            socket=$1
            [ -n "$socket" ] || exit 0
            self=$$
            for entry in /proc/[0-9]*; do
              pid=${entry#/proc/}
              [ "$pid" = "$self" ] && continue
              if grep -qF "$socket" "$entry/cmdline" 2>/dev/null; then
                kill "$pid" 2>/dev/null
              fi
            done
            rm -f "$socket" 2>/dev/null
            exit 0
            """;

    private final DtachInstaller dtachInstaller;
    private final KubernetesClientPool clientPool;
    private final TerminalProperties terminalProperties;

    public KubernetesTerminalSessionGateway(DtachInstaller dtachInstaller,
                                            KubernetesClientPool clientPool,
                                            TerminalProperties terminalProperties) {
        this.dtachInstaller = dtachInstaller;
        this.clientPool = clientPool;
        this.terminalProperties = terminalProperties;
    }

    @Override
    public TerminalSession open(Environment environment, String namespace, String podName, String container, String sessionKey, StreamSink sink) {
        // The stream outlives this call and owns its client, so it gets a dedicated one rather than a
        // pooled instance that other callers would still be using when the terminal closes.
        KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer());
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        handle.add(client);

        String socketPath = resolveSocketPath(environment, namespace, podName, container, sessionKey);

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
                        public void onFailure(Throwable throwable, Response response) {
                            log.warn("Terminal session failed for pod {}/{}: {}", namespace, podName, throwable.getMessage());
                            closeSinkWithError(sink);
                        }
                    })
                    // The socket travels as a positional argument rather than inside the script text,
                    // so it is data to the shell and can never be read as syntax.
                    .exec("sh", "-c", TERMINAL_SCRIPT, "sh", socketPath == null ? "" : socketPath);
        } catch (RuntimeException e) {
            handle.close();
            throw e;
        }

        handle.add(watch);
        OutputStream stdin = watch.getInput();
        return new KubernetesTerminalSession(stdin, handle);
    }

    @Override
    public void terminate(Environment environment, String namespace, String podName, String container, String sessionKey) {
        String socketPath = TerminalSessionSupport.socketPath(sessionKey);
        if (socketPath == null) {
            return;
        }
        // Called from a WebSocket close callback; the exec below is a network round trip that must
        // not hold up the container's event loop.
        Thread.startVirtualThread(() -> {
            try {
                runTerminate(environment, namespace, podName, container, socketPath);
            } catch (RuntimeException exception) {
                log.debug("Failed to terminate terminal session for {}/{}", namespace, podName, exception);
            }
        });
    }

    /**
     * Installs dtach on demand and returns the socket the shell should live on, or null when this
     * container cannot host a resumable shell and a plain one should be opened instead.
     */
    private String resolveSocketPath(Environment environment, String namespace, String podName, String container, String sessionKey) {
        if (!terminalProperties.isResumeEnabled()) {
            return null;
        }
        String socketPath = TerminalSessionSupport.socketPath(sessionKey);
        if (socketPath == null) {
            return null;
        }
        boolean installed = dtachInstaller.ensureInstalled(
                clientPool.get(environment.getKubernetesApiServer()), namespace, podName, container);
        return installed ? socketPath : null;
    }

    private void runTerminate(Environment environment, String namespace, String podName, String container, String socketPath) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch finished = new CountDownLatch(1);

        try (ExecWatch _ = clientPool.get(environment.getKubernetesApiServer())
                .pods().inNamespace(namespace).withName(podName)
                .inContainer(container)
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        finished.countDown();
                    }

                    @Override
                    public void onFailure(Throwable throwable, Response response) {
                        finished.countDown();
                    }
                })
                .exec("sh", "-c", TERMINATE_SCRIPT, "sh", socketPath)) {

            if (!finished.await(TERMINATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.debug("Timed out terminating terminal session {} in {}/{}", socketPath, namespace, podName);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
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
