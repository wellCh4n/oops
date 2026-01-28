package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.annotation.WithoutKubernetes;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.EnvironmentService;
import org.springframework.web.bind.annotation.*;

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

    @WithoutKubernetes
    @GetMapping
    public Result<List<Environment>> getEnvironments() {
        return Result.success(environmentService.getEnvironments());
    }

    @WithoutKubernetes
    @PutMapping("{id}")
    public Result<Boolean> updateEnvironment(@PathVariable String id,
                                             @RequestBody Environment environment) {
        return Result.success(environmentService.updateEnvironment(id, environment));
    }

    @WithoutKubernetes
    @PostMapping
    public Result<Boolean> createEnvironment(@RequestBody Environment environment) {
        return Result.success(environmentService.createEnvironment(environment));
    }

    @WithoutKubernetes
    @DeleteMapping("{id}")
    public Result<Boolean> deleteEnvironment(@PathVariable String id) {
        return Result.success(environmentService.deleteEnvironment(id));
    }

    @WithoutKubernetes
    @PostMapping("test/{id}")
    public Result<Boolean> testEnvironment(@PathVariable String id) {
        return Result.success(environmentService.testConnection(id));
    }
}
