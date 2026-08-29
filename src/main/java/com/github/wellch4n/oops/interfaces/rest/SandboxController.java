package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.SandboxExecutionRequest;
import com.github.wellch4n.oops.application.dto.SandboxInstanceCreateRequest;
import com.github.wellch4n.oops.application.dto.SandboxInstanceExecRequest;
import com.github.wellch4n.oops.application.port.PodFileSystemGateway.PodFileEntry;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxExecutionResult;
import com.github.wellch4n.oops.application.service.PodFileSystemService;
import com.github.wellch4n.oops.application.service.SandboxExecutionService;
import com.github.wellch4n.oops.application.service.SandboxInstanceService;
import com.github.wellch4n.oops.application.service.SandboxInstanceService.SandboxTerminalTarget;
import com.github.wellch4n.oops.application.service.SandboxRuntimeService;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstance;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.interfaces.rest.PodFileSystemController.DirectoryCreateRequest;
import com.github.wellch4n.oops.interfaces.rest.PodFileSystemController.FileContentResponse;
import com.github.wellch4n.oops.interfaces.rest.PodFileSystemController.FileRenameRequest;
import com.github.wellch4n.oops.interfaces.rest.PodFileSystemController.FileSaveRequest;
import com.github.wellch4n.oops.shared.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping({"/api/sandbox", "/openapi/sandbox"})
public class SandboxController {

    private static final String SANDBOX_CONTAINER = "sandbox";

    private final SandboxExecutionService sandboxExecutionService;
    private final SandboxInstanceService sandboxInstanceService;
    private final SandboxRuntimeService sandboxRuntimeService;
    private final PodFileSystemService podFileSystemService;

    public SandboxController(SandboxExecutionService sandboxExecutionService,
                             SandboxInstanceService sandboxInstanceService,
                             SandboxRuntimeService sandboxRuntimeService,
                             PodFileSystemService podFileSystemService) {
        this.sandboxExecutionService = sandboxExecutionService;
        this.sandboxInstanceService = sandboxInstanceService;
        this.sandboxRuntimeService = sandboxRuntimeService;
        this.podFileSystemService = podFileSystemService;
    }

    @GetMapping("/images")
    public Result<List<String>> listImages() {
        return Result.success(sandboxRuntimeService.list());
    }

    @PostMapping("/executions")
    public Object execute(@RequestBody SandboxExecutionRequest request,
                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        boolean streaming = Boolean.TRUE.equals(request.stream());
        if (streaming) {
            return sandboxExecutionService.stream(request, callerUserId);
        }
        SandboxExecutionResult result = sandboxExecutionService.execute(request, callerUserId);
        return Result.success(result);
    }

    @PostMapping("/instances")
    public Result<SandboxInstance> create(@RequestBody SandboxInstanceCreateRequest request,
                                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        return Result.success(sandboxInstanceService.create(request, callerUserId));
    }

    @GetMapping("/instances")
    public Result<List<SandboxInstance>> list(@RequestParam(value = "environment", required = false) String environment,
                                              @RequestParam(value = "image", required = false) String image,
                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        return Result.success(sandboxInstanceService.list(callerUserId, environment, image));
    }

    @GetMapping("/instances/{id}")
    public Result<SandboxInstance> get(@PathVariable("id") String id,
                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        return Result.success(sandboxInstanceService.get(id, callerUserId));
    }

    @DeleteMapping("/instances/{id}")
    public Result<Void> delete(@PathVariable("id") String id,
                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        sandboxInstanceService.delete(id, callerUserId);
        return Result.success(null);
    }

    @PostMapping("/instances/{id}/exec")
    public Object exec(@PathVariable("id") String id,
                       @RequestBody SandboxInstanceExecRequest request,
                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        boolean streaming = Boolean.TRUE.equals(request.stream());
        if (streaming) {
            return sandboxInstanceService.streamExec(id, request, callerUserId);
        }
        SandboxExecutionResult result = sandboxInstanceService.exec(id, request, callerUserId);
        return Result.success(result);
    }

    @GetMapping("/instances/{id}/files")
    public Result<List<PodFileEntry>> listFiles(@PathVariable("id") String id,
                                                @RequestParam(value = "path", required = false) String path,
                                                @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        String resolvedPath = (path == null || path.isBlank()) ? "/" : path;
        return Result.success(podFileSystemService.listDirectory(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, resolvedPath));
    }

    @GetMapping("/instances/{id}/files/download")
    public void downloadFile(@PathVariable("id") String id,
                             @RequestParam(value = "path") String path,
                             @AuthenticationPrincipal AuthUserPrincipal principal,
                             HttpServletResponse response) throws Exception {
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        long fileSize = podFileSystemService.getFileSize(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, path);
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
        podFileSystemService.streamFile(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, path, response.getOutputStream());
    }

    @GetMapping("/instances/{id}/files/content")
    public Result<FileContentResponse> getFileContent(@PathVariable("id") String id,
                                                      @RequestParam(value = "path") String path,
                                                      @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        String content = podFileSystemService.readTextFile(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, path);
        return Result.success(new FileContentResponse(path, content));
    }

    @PutMapping("/instances/{id}/files/content")
    public Result<Void> saveFileContent(@PathVariable("id") String id,
                                        @RequestBody FileSaveRequest request,
                                        @AuthenticationPrincipal AuthUserPrincipal principal) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new BizException("Path is required");
        }
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        podFileSystemService.writeTextFile(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, request.path(), request.content());
        return Result.success(null);
    }

    @PostMapping("/instances/{id}/files/upload")
    public Result<Void> uploadFile(@PathVariable("id") String id,
                                   @RequestParam(value = "path") String path,
                                   @RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal AuthUserPrincipal principal) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("File is required");
        }
        long maxUploadSizeBytes = podFileSystemService.getMaxUploadSizeBytes();
        if (file.getSize() > maxUploadSizeBytes) {
            long maxMB = maxUploadSizeBytes / (1024 * 1024);
            throw new BizException("File too large: " + file.getSize() + " bytes (max " + maxMB + " MB)");
        }
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        String targetPath = PodFileSystemController.resolveUploadTargetPath(path, file.getOriginalFilename());
        podFileSystemService.uploadFile(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, targetPath, file.getInputStream());
        return Result.success(null);
    }

    @DeleteMapping("/instances/{id}/files")
    public Result<Void> deleteFile(@PathVariable("id") String id,
                                   @RequestParam(value = "path") String path,
                                   @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        podFileSystemService.deletePath(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, path);
        return Result.success(null);
    }

    @PostMapping("/instances/{id}/files/rename")
    public Result<Void> renameFile(@PathVariable("id") String id,
                                   @RequestBody FileRenameRequest request,
                                   @AuthenticationPrincipal AuthUserPrincipal principal) {
        if (request == null || request.fromPath() == null || request.fromPath().isBlank()
                || request.toPath() == null || request.toPath().isBlank()) {
            throw new BizException("fromPath and toPath are required");
        }
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        podFileSystemService.renamePath(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, request.fromPath(), request.toPath());
        return Result.success(null);
    }

    @PostMapping("/instances/{id}/files/directory")
    public Result<Void> createDirectory(@PathVariable("id") String id,
                                        @RequestBody DirectoryCreateRequest request,
                                        @AuthenticationPrincipal AuthUserPrincipal principal) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new BizException("Path is required");
        }
        String callerUserId = principal != null ? principal.userId() : null;
        SandboxTerminalTarget target = sandboxInstanceService.resolveTerminalTarget(id, callerUserId);
        podFileSystemService.createDirectory(target.environment(), target.namespace(), target.podName(), SANDBOX_CONTAINER, request.path());
        return Result.success(null);
    }
}
