package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.port.PodFileSystemGateway.PodFileEntry;
import com.github.wellch4n.oops.application.service.PodFileSystemService;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.shared.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{name}/pods/{pod}/files")
public class PodFileSystemController {

    private final PodFileSystemService podFileSystemService;

    public PodFileSystemController(PodFileSystemService podFileSystemService) {
        this.podFileSystemService = podFileSystemService;
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
}
