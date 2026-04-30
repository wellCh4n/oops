package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.interfaces.dto.EnvironmentResponse;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

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
    public Result<List<EnvironmentResponse>> getEnvironments() {
        List<EnvironmentResponse> responses = environmentService.getEnvironments().stream()
                .map(EnvironmentResponse::from)
                .toList();
        return Result.success(responses);
    }

    @GetMapping("{id}")
    public Result<EnvironmentResponse> getEnvironment(@PathVariable String id) {
        Environment environment = environmentService.getEnvironmentById(id);
        return Result.success(environment == null ? null : EnvironmentResponse.from(environment));
    }

    @PutMapping("{id}")
    public Result<Boolean> updateEnvironment(@PathVariable String id,
                                             @RequestBody Environment environment) {
        return Result.success(environmentService.updateEnvironment(id, environment));
    }

    @PostMapping
    public Result<EnvironmentResponse> createEnvironment(@RequestBody Environment environment) {
        return Result.success(EnvironmentResponse.from(environmentService.createEnvironment(environment)));
    }

    @DeleteMapping("{id}")
    public Result<Boolean> deleteEnvironment(@PathVariable String id) {
        return Result.success(environmentService.deleteEnvironment(id));
    }
}
