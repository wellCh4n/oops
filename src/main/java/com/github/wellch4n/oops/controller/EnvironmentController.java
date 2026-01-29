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

    @PutMapping("{id}")
    public Result<Boolean> updateEnvironment(@PathVariable String id,
                                             @RequestBody Environment environment) {
        return Result.success(environmentService.updateEnvironment(id, environment));
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createEnvironmentStream(@RequestBody Environment environment) {
        return environmentService.createEnvironmentStream(environment);
    }

    @DeleteMapping("{id}")
    public Result<Boolean> deleteEnvironment(@PathVariable String id) {
        return Result.success(environmentService.deleteEnvironment(id));
    }

    @PostMapping("test/{id}")
    public Result<Boolean> testEnvironment(@PathVariable String id) {
        return Result.success(environmentService.testConnection(id));
    }

    @PostMapping("test")
    public Result<Boolean> testEnvironment(@RequestBody Environment environment) {
        return Result.success(environmentService.testConnection(environment));
    }

    @PostMapping("/kubernetes/test")
    public Result<Boolean> testKubernetes(@RequestBody Environment environment) {
        return Result.success(environmentService.testKubernetesConnection(environment));
    }

    @PostMapping("/registry/test")
    public Result<Boolean> testRegistry(@RequestBody Environment environment) {
        return Result.success(environmentService.testRegistryConnection(environment));
    }
}
