package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.EnvironmentDto;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.shared.log.Loggable;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
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
    public Result<List<EnvironmentDto>> getEnvironments() {
        List<EnvironmentDto> responses = environmentService.getEnvironments().stream()
                .map(EnvironmentDto::from)
                .toList();
        return Result.success(responses);
    }

    @GetMapping("{id}")
    public Result<EnvironmentDto> getEnvironment(@PathVariable String id) {
        Environment environment = environmentService.getEnvironmentById(id);
        return Result.success(environment == null ? null : EnvironmentDto.from(environment));
    }

    @PutMapping("{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Loggable(operation = "UPDATE_ENV", resourceType = "Environment")
    public Result<Boolean> updateEnvironment(@PathVariable String id,
                                             @RequestBody Environment environment) {
        return Result.success(environmentService.updateEnvironment(id, environment));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Loggable(operation = "CREATE_ENV", resourceType = "Environment")
    public Result<EnvironmentDto> createEnvironment(@RequestBody Environment environment) {
        return Result.success(EnvironmentDto.from(environmentService.createEnvironment(environment)));
    }

    @DeleteMapping("{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Loggable(operation = "DELETE_ENV", resourceType = "Environment")
    public Result<Boolean> deleteEnvironment(@PathVariable String id) {
        return Result.success(environmentService.deleteEnvironment(id));
    }
}
