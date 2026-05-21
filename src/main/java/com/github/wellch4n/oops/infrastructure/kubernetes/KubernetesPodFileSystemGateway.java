package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.PodFileSystemGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.shared.exception.BizException;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KubernetesPodFileSystemGateway implements PodFileSystemGateway {

    private static final long EXEC_TIMEOUT_SECONDS = 15;
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 120;
    private static final int UPLOAD_REQUEST_TIMEOUT_MILLIS = 600_000;
    private static final int UPLOAD_MAX_ATTEMPTS = 3;
    private static final long UPLOAD_RETRY_BACKOFF_MILLIS = 500;
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private static final String LIST_SCRIPT = """
            set -e
            target=$1
            if [ -z "$target" ]; then target=/; fi
            if [ ! -e "$target" ]; then echo __OOPS_NOT_FOUND__; exit 0; fi
            if [ ! -d "$target" ]; then echo __OOPS_NOT_DIR__; exit 0; fi
            cd "$target" 2>/dev/null || { echo __OOPS_DENIED__; exit 0; }
            for entry in .* *; do
              [ "$entry" = "." ] && continue
              [ "$entry" = ".." ] && continue
              [ "$entry" = "*" ] && continue
              [ "$entry" = ".*" ] && continue
              [ ! -e "$entry" ] && [ ! -L "$entry" ] && continue
              if [ -L "$entry" ]; then
                kind=L; size=0
              elif [ -d "$entry" ]; then
                kind=D; size=0
              elif [ -f "$entry" ]; then
                kind=F
                size=$(wc -c < "$entry" 2>/dev/null | tr -d ' ' || echo 0)
              else
                kind=O; size=0
              fi
              printf '%s\\t%s\\t%s\\n' "$kind" "$size" "$entry"
            done
            """;
    private static final String FILE_SIZE_SCRIPT = """
            target=$1
            if [ ! -f "$target" ]; then echo __OOPS_NOT_FILE__ >&2; exit 1; fi
            wc -c < "$target" 2>/dev/null | tr -d ' '
            """;
    private static final String CAT_FILE_SCRIPT = """
            target=$1
            if [ ! -f "$target" ]; then echo __OOPS_NOT_FILE__ >&2; exit 1; fi
            cat "$target"
            """;
    private static final String DELETE_SCRIPT = """
            target=$1
            if [ -z "$target" ] || [ "$target" = "/" ]; then echo __OOPS_REFUSED__ >&2; exit 1; fi
            if [ ! -e "$target" ] && [ ! -L "$target" ]; then echo __OOPS_NOT_FOUND__ >&2; exit 1; fi
            rm -rf -- "$target" 2>&1 || { echo __OOPS_DELETE_FAILED__ >&2; exit 1; }
            """;
    private static final String RENAME_SCRIPT = """
            from=$1
            to=$2
            if [ -z "$from" ] || [ -z "$to" ] || [ "$from" = "/" ] || [ "$to" = "/" ]; then echo __OOPS_REFUSED__ >&2; exit 1; fi
            if [ ! -e "$from" ] && [ ! -L "$from" ]; then echo __OOPS_NOT_FOUND__ >&2; exit 1; fi
            if [ -e "$to" ] || [ -L "$to" ]; then echo __OOPS_TARGET_EXISTS__ >&2; exit 1; fi
            mv -- "$from" "$to" 2>&1 || { echo __OOPS_RENAME_FAILED__ >&2; exit 1; }
            """;

    private final KubernetesClientPool clientPool;

    public KubernetesPodFileSystemGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public List<PodFileEntry> listDirectory(Environment environment, String namespace, String podName, String container, String path) {
        String normalizedPath = (path == null || path.isBlank()) ? "/" : path;
        if (normalizedPath.contains("\0") || normalizedPath.contains("\n")) {
            throw new BizException("Invalid path");
        }

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
                        log.warn("List directory failed for {}/{}:{}: {}", namespace, podName, normalizedPath, throwable.getMessage());
                        finished.countDown();
                    }
                })
                .exec("sh", "-c", LIST_SCRIPT + "\n", "sh", normalizedPath)) {

            if (!finished.await(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new BizException("Listing directory timed out");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException("Listing directory interrupted");
        }

        if (stdout.size() > MAX_OUTPUT_BYTES) {
            throw new BizException("Directory listing too large");
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        return parseOutput(output, normalizedPath);
    }

    @Override
    public long getFileSize(Environment environment, String namespace, String podName, String container, String path) {
        sanitizePath(path);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

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
                        log.warn("Get file size failed for {}/{}:{}: {}", namespace, podName, path, throwable.getMessage());
                        failure.set(throwable);
                        finished.countDown();
                    }
                })
                .exec("sh", "-c", FILE_SIZE_SCRIPT + "\n", "sh", path)) {

            if (!finished.await(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new BizException("Get file size timed out");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException("Get file size interrupted");
        }

        String errOutput = stderr.toString(StandardCharsets.UTF_8).trim();
        if (failure.get() != null) {
            throw new BizException("Get file size failed");
        }
        if (errOutput.contains("__OOPS_NOT_FILE__")) {
            throw new BizException("Not a file");
        }
        String output = stdout.toString(StandardCharsets.UTF_8).trim();
        if (output.contains("__OOPS_NOT_FILE__")) {
            throw new BizException("Not a file");
        }
        try {
            return Long.parseLong(output);
        } catch (NumberFormatException e) {
            throw new BizException("Failed to parse file size");
        }
    }

    @Override
    public void streamFile(Environment environment, String namespace, String podName, String container, String path, OutputStream outputStream) {
        sanitizePath(path);

        CountDownLatch finished = new CountDownLatch(1);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ExecWatch _ = clientPool.get(environment.getKubernetesApiServer())
                .pods().inNamespace(namespace).withName(podName)
                .inContainer(container)
                .writingOutput(outputStream)
                .writingError(stderr)
                .usingListener(new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        finished.countDown();
                    }

                    @Override
                    public void onFailure(Throwable throwable, Response response) {
                        log.warn("Stream file failed for {}/{}:{}: {}", namespace, podName, path, throwable.getMessage());
                        failure.set(throwable);
                        finished.countDown();
                    }
                })
                .exec("sh", "-c", CAT_FILE_SCRIPT + "\n", "sh", path)) {

            if (!finished.await(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new BizException("Download timed out");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException("Download interrupted");
        }

        String errOutput = stderr.toString(StandardCharsets.UTF_8);
        if (failure.get() != null) {
            throw new BizException("Download failed");
        }
        if (errOutput.contains("__OOPS_NOT_FILE__")) {
            throw new BizException("File not found");
        }
    }

    @Override
    public void uploadFile(Environment environment, String namespace, String podName, String container, String path, InputStream inputStream) {
        sanitizePath(path);
        if ("/".equals(path)) {
            throw new BizException("Refusing to upload to root path");
        }

        byte[] payload;
        try {
            payload = inputStream.readAllBytes();
        } catch (java.io.IOException ioe) {
            throw new BizException("Upload failed: " + ioe.getMessage());
        }

        var requestConfig = new RequestConfigBuilder()
                .withUploadRequestTimeout(UPLOAD_REQUEST_TIMEOUT_MILLIS)
                .build();

        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= UPLOAD_MAX_ATTEMPTS; attempt++) {
            try {
                NamespacedKubernetesClient client = (NamespacedKubernetesClient) clientPool.get(environment.getKubernetesApiServer());
                Boolean uploaded = client.withRequestConfig(requestConfig)
                        .call(scoped -> scoped.pods().inNamespace(namespace).withName(podName)
                                .inContainer(container)
                                .file(path)
                                .upload(new java.io.ByteArrayInputStream(payload)));
                if (Boolean.TRUE.equals(uploaded)) {
                    return;
                }
                log.warn("Upload returned false for {}/{}:{} (attempt {}/{})",
                        namespace, podName, path, attempt, UPLOAD_MAX_ATTEMPTS);
            } catch (Exception exception) {
                lastFailure = exception;
                log.warn("Upload attempt {}/{} failed for {}/{}:{}: {}",
                        attempt, UPLOAD_MAX_ATTEMPTS, namespace, podName, path, exception.getMessage());
            }

            if (attempt < UPLOAD_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(UPLOAD_RETRY_BACKOFF_MILLIS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BizException("Upload interrupted");
                }
            }
        }

        if (lastFailure != null) {
            throw new BizException("Upload failed: " + lastFailure.getMessage());
        }
        throw new BizException("Upload failed after " + UPLOAD_MAX_ATTEMPTS + " attempts");
    }

    @Override
    public void deletePath(Environment environment, String namespace, String podName, String container, String path) {
        sanitizePath(path);
        if ("/".equals(path)) {
            throw new BizException("Refusing to delete root path");
        }
        ExecResult result = runExec(environment, namespace, podName, container, EXEC_TIMEOUT_SECONDS, DELETE_SCRIPT, "sh", path);
        String stderr = result.stderr();
        if (stderr.contains("__OOPS_REFUSED__")) {
            throw new BizException("Refused to delete path");
        }
        if (stderr.contains("__OOPS_NOT_FOUND__")) {
            throw new BizException("Path not found");
        }
        if (result.failed() || stderr.contains("__OOPS_DELETE_FAILED__")) {
            throw new BizException("Delete failed");
        }
    }

    @Override
    public void renamePath(Environment environment, String namespace, String podName, String container, String fromPath, String toPath) {
        sanitizePath(fromPath);
        sanitizePath(toPath);
        if ("/".equals(fromPath) || "/".equals(toPath)) {
            throw new BizException("Refusing to rename root path");
        }
        ExecResult result = runExec(environment, namespace, podName, container, EXEC_TIMEOUT_SECONDS, RENAME_SCRIPT, "sh", fromPath, toPath);
        String stderr = result.stderr();
        if (stderr.contains("__OOPS_REFUSED__")) {
            throw new BizException("Refused to rename path");
        }
        if (stderr.contains("__OOPS_NOT_FOUND__")) {
            throw new BizException("Source path not found");
        }
        if (stderr.contains("__OOPS_TARGET_EXISTS__")) {
            throw new BizException("Target path already exists");
        }
        if (result.failed() || stderr.contains("__OOPS_RENAME_FAILED__")) {
            throw new BizException("Rename failed");
        }
    }

    private ExecResult runExec(Environment environment, String namespace, String podName, String container,
                               long timeoutSeconds, String script, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        String[] command = new String[args.length + 3];
        command[0] = "sh";
        command[1] = "-c";
        command[2] = script + "\n";
        System.arraycopy(args, 0, command, 3, args.length);

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
                        log.warn("Exec failed for {}/{}: {}", namespace, podName, throwable.getMessage());
                        failure.set(throwable);
                        finished.countDown();
                    }
                })
                .exec(command)) {

            if (!finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new BizException("Operation timed out");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException("Operation interrupted");
        }
        return new ExecResult(stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8), failure.get());
    }

    private record ExecResult(String stdout, String stderr, Throwable throwable) {
        boolean failed() {
            return throwable != null;
        }
    }

    private void sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException("Path is required");
        }
        if (path.contains("\0") || path.contains("\n")) {
            throw new BizException("Invalid path");
        }
    }

    private List<PodFileEntry> parseOutput(String output, String basePath) {
        if (output.contains("__OOPS_NOT_FOUND__")) {
            throw new BizException("Path not found");
        }
        if (output.contains("__OOPS_NOT_DIR__")) {
            throw new BizException("Not a directory");
        }
        if (output.contains("__OOPS_DENIED__")) {
            throw new BizException("Permission denied");
        }

        List<PodFileEntry> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            PodFileEntry.FileType type = switch (parts[0]) {
                case "D" -> PodFileEntry.FileType.DIRECTORY;
                case "F" -> PodFileEntry.FileType.FILE;
                case "L" -> PodFileEntry.FileType.SYMLINK;
                default -> PodFileEntry.FileType.OTHER;
            };
            Long size;
            try {
                size = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                size = null;
            }
            String name = parts[2];
            entries.add(new PodFileEntry(name, joinPath(basePath, name), type, size));
        }

        entries.sort(Comparator
                .comparing((PodFileEntry entry) -> entry.type() != PodFileEntry.FileType.DIRECTORY)
                .thenComparing(entry -> entry.name().toLowerCase()));
        return entries;
    }

    private String joinPath(String base, String name) {
        if (base.endsWith("/")) {
            return base + name;
        }
        return base + "/" + name;
    }
}
