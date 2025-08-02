package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.EnvironmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
