package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.port.PodFileSystemGateway.PodFileEntry;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.application.service.PodFileSystemService;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.shared.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{name}/pods/{pod}/files")
public class PodFileSystemController {

    private final PodFileSystemService podFileSystemService;
    private final EnvironmentRepository environmentRepository;

    public PodFileSystemController(PodFileSystemService podFileSystemService,
                                   EnvironmentRepository environmentRepository) {
        this.podFileSystemService = podFileSystemService;
        this.environmentRepository = environmentRepository;
    }

    @GetMapping
    public Result<List<PodFileEntry>> listDirectory(@PathVariable String namespace,
                                                    @PathVariable String name,
                                                    @PathVariable String pod,
                                                    @RequestParam(value = "env") String env,
                                                    @RequestParam(value = "container", required = false) String container,
                                                    @RequestParam(value = "path", required = false) String path) {
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        String resolvedPath = (path == null || path.isBlank()) ? "/" : path;
        return Result.success(podFileSystemService.listDirectory(env, namespace, pod, resolvedContainer, resolvedPath));
    }

    @GetMapping("/download")
    public void downloadFile(@PathVariable String namespace,
                             @PathVariable String name,
                             @PathVariable String pod,
                             @RequestParam(value = "env") String env,
                             @RequestParam(value = "container", required = false) String container,
                             @RequestParam(value = "path") String path,
                             HttpServletResponse response) throws Exception {
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        long fileSize = podFileSystemService.getFileSize(env, namespace, pod, resolvedContainer, path);
        long maxDownloadSizeBytes = podFileSystemService.getMaxDownloadSizeBytes();
        if (fileSize > maxDownloadSizeBytes) {
            long maxMB = maxDownloadSizeBytes / (1024 * 1024);
            throw new BizException("File too large: " + fileSize + " bytes (max " + maxMB + " MB)");
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        response.setContentLengthLong(fileSize);
        podFileSystemService.streamFile(env, namespace, pod, resolvedContainer, path, response.getOutputStream());
    }

    @GetMapping("/content")
    public Result<FileContentResponse> getFileContent(@PathVariable String namespace,
                                                      @PathVariable String name,
                                                      @PathVariable String pod,
                                                      @RequestParam(value = "env") String env,
                                                      @RequestParam(value = "container", required = false) String container,
                                                      @RequestParam(value = "path") String path) {
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        Environment environment = resolveEnvironment(env);
        String content = podFileSystemService.readTextFile(environment, namespace, pod, resolvedContainer, path);
        return Result.success(new FileContentResponse(path, content));
    }

    @PutMapping("/content")
    public Result<Void> saveFileContent(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String pod,
                                        @RequestParam(value = "env") String env,
                                        @RequestParam(value = "container", required = false) String container,
                                        @RequestBody FileSaveRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new BizException("Path is required");
        }
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        Environment environment = resolveEnvironment(env);
        podFileSystemService.writeTextFile(environment, namespace, pod, resolvedContainer, request.path(), request.content());
        return Result.success(null);
    }

    @PostMapping("/upload")
    public Result<Void> uploadFile(@PathVariable String namespace,
                                   @PathVariable String name,
                                   @PathVariable String pod,
                                   @RequestParam(value = "env") String env,
                                   @RequestParam(value = "container", required = false) String container,
                                   @RequestParam(value = "path") String path,
                                   @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("File is required");
        }
        long maxUploadSizeBytes = podFileSystemService.getMaxUploadSizeBytes();
        if (file.getSize() > maxUploadSizeBytes) {
            long maxMB = maxUploadSizeBytes / (1024 * 1024);
            throw new BizException("File too large: " + file.getSize() + " bytes (max " + maxMB + " MB)");
        }
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        String targetPath = resolveUploadTargetPath(path, file.getOriginalFilename());
        podFileSystemService.uploadFile(env, namespace, pod, resolvedContainer, targetPath, file.getInputStream());
        return Result.success(null);
    }

    @DeleteMapping
    public Result<Void> deletePath(@PathVariable String namespace,
                                   @PathVariable String name,
                                   @PathVariable String pod,
                                   @RequestParam(value = "env") String env,
                                   @RequestParam(value = "container", required = false) String container,
                                   @RequestParam(value = "path") String path) {
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        podFileSystemService.deletePath(env, namespace, pod, resolvedContainer, path);
        return Result.success(null);
    }

    @PostMapping("/directory")
    public Result<Void> createDirectory(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String pod,
                                        @RequestParam(value = "env") String env,
                                        @RequestParam(value = "container", required = false) String container,
                                        @RequestBody DirectoryCreateRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new BizException("Path is required");
        }
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        podFileSystemService.createDirectory(env, namespace, pod, resolvedContainer, request.path());
        return Result.success(null);
    }

    @PostMapping("/rename")
    public Result<Void> renamePath(@PathVariable String namespace,
                                   @PathVariable String name,
                                   @PathVariable String pod,
                                   @RequestParam(value = "env") String env,
                                   @RequestParam(value = "container", required = false) String container,
                                   @RequestBody FileRenameRequest request) {
        if (request == null || request.fromPath() == null || request.fromPath().isBlank()
                || request.toPath() == null || request.toPath().isBlank()) {
            throw new BizException("fromPath and toPath are required");
        }
        String resolvedContainer = (container == null || container.isBlank()) ? name : container;
        podFileSystemService.renamePath(env, namespace, pod, resolvedContainer, request.fromPath(), request.toPath());
        return Result.success(null);
    }

    private Environment resolveEnvironment(String environmentName) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        return environment;
    }

    static String resolveUploadTargetPath(String rawPath, String originalFilename) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new BizException("Path is required");
        }
        String trimmed = rawPath.trim();
        if (trimmed.endsWith("/")) {
            String safeName = sanitizeUploadFileName(originalFilename);
            if (safeName == null || safeName.isBlank()) {
                throw new BizException("Upload filename is required");
            }
            return trimmed + safeName;
        }
        return trimmed;
    }

    private static String sanitizeUploadFileName(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        String trimmed = originalFilename.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int slashIndex = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String tail = slashIndex >= 0 ? trimmed.substring(slashIndex + 1) : trimmed;
        if (tail.isEmpty() || tail.equals(".") || tail.equals("..")) {
            throw new BizException("Invalid upload filename");
        }
        if (tail.contains("\0") || tail.contains("\n")) {
            throw new BizException("Invalid upload filename");
        }
        return tail;
    }

    public record FileContentResponse(String path, String content) {
    }

    public record FileSaveRequest(String path, String content) {
    }

    public record FileRenameRequest(String fromPath, String toPath) {
    }

    public record DirectoryCreateRequest(String path) {
    }
}
