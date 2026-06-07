package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.AssetEntry;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadResult;
import com.github.wellch4n.oops.application.service.StaticAssetService;
import com.github.wellch4n.oops.interfaces.dto.Result;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
public class StaticAssetController {

    private final StaticAssetService staticAssetService;

    public StaticAssetController(StaticAssetService staticAssetService) {
        this.staticAssetService = staticAssetService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<AssetEntry>> list(@RequestParam(value = "path", required = false) String path) {
        return Result.success(staticAssetService.list(path));
    }

    @PostMapping("/upload-url")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ObjectStorageUploadResult> createUploadUrl(@RequestBody AssetUploadRequest request) {
        return Result.success(staticAssetService.createUploadUrl(
                request.path(), request.fileName(), request.contentType(), request.fileSize()));
    }

    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> delete(@RequestParam("key") String key) {
        staticAssetService.delete(key);
        return Result.success(true);
    }

    public record AssetUploadRequest(String path, String fileName, String contentType, Long fileSize) {
    }
}
