package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.application.dto.PipelineDto;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.shared.log.Loggable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping({
        "/api/namespaces/{namespace}/applications/{name}/pipelines",
        "/openapi/namespaces/{namespace}/applications/{name}/pipelines"
})
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping
    public Result<Page<PipelineDto>> getPipelines(@PathVariable String namespace,
                                                       @PathVariable String name,
                                                       @RequestParam(required = false) String environment,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.success(pipelineService.getPipelines(namespace, name, environment, page, size));
    }

    @GetMapping("/{id}")
    public Result<PipelineDto> getPipeline(@PathVariable String namespace,
                                                @PathVariable String name,
                                                @PathVariable String id) {
        return Result.success(pipelineService.getPipelineDetail(namespace, name, id));
    }

    @PutMapping("/{id}/stop")
    @PreAuthorize("isAuthenticated()")
    @Loggable(operation = "STOP_PIPELINE", resourceType = "Pipeline")
    public Result<Boolean> stopPipeline(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String id,
                                        Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(pipelineService.stopPipeline(namespace, name, id, principal.userId()));
    }

    @PutMapping("/{id}/deploy")
    @PreAuthorize("isAuthenticated()")
    @Loggable(operation = "DEPLOY_PIPELINE", resourceType = "Pipeline")
    public Result<Boolean> deployPipeline(@PathVariable String namespace,
                                          @PathVariable String name,
                                          @PathVariable String id,
                                          Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(pipelineService.deployPipeline(namespace, name, id, principal.userId()));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("isAuthenticated()")
    @Loggable(operation = "ROLLBACK", resourceType = "Pipeline")
    public Result<String> rollbackPipeline(@PathVariable String namespace,
                                           @PathVariable String name,
                                           @PathVariable String id,
                                           Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(pipelineService.rollback(namespace, name, id, principal.userId()));
    }
}
