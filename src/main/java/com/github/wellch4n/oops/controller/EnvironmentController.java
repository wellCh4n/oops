package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.EnvironmentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

@RestController
@RequestMapping("/api/environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;

    public EnvironmentController(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @GetMapping
    public Result<List<Environment>> getEnvironments() {
        return Result.success(environmentService.getEnvironments());
    }

    @GetMapping("{id}")
    public Result<Environment> getEnvironment(@PathVariable String id) {
        return Result.success(environmentService.getEnvironmentById(id));
    }

    @PutMapping("{id}")
    public Result<Boolean> updateEnvironment(@PathVariable String id,
                                             @RequestBody Environment environment) {
        return Result.success(environmentService.updateEnvironment(id, environment));
    }

    @PostMapping
    public Result<Environment> createEnvironment(@RequestBody Environment environment) {
        return Result.success(environmentService.createEnvironment(environment));
    }

    @PostMapping("validate/kubernetes")
    public Result<EnvironmentService.KubernetesValidationResult> validateKubernetes(@RequestBody EnvironmentService.KubernetesValidationRequest request) {
        return Result.success(environmentService.validateKubernetes(request));
    }

    @PostMapping("create-namespace")
    public Result<Boolean> createNamespace(@RequestBody EnvironmentService.KubernetesValidationRequest request) {
        return Result.success(environmentService.createNamespace(request));
    }

    @PostMapping("validate/image-repository")
    public Result<Boolean> validateImageRepository(@RequestBody Environment.ImageRepository imageRepository) {
        return Result.success(environmentService.validateImageRepository(imageRepository));
    }

    @PostMapping(path = "{id}/initialize", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter initializeEnvironmentStream(@PathVariable String id) {
        return environmentService.initializeEnvironmentStream(id);
    }

    @DeleteMapping("{id}")
    public Result<Boolean> deleteEnvironment(@PathVariable String id) {
        return Result.success(environmentService.deleteEnvironment(id));
    }
}
