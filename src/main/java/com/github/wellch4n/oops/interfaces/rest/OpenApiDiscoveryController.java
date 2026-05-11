package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.EnvironmentDto;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.application.service.NamespaceService;
import com.github.wellch4n.oops.domain.namespace.Namespace;
import com.github.wellch4n.oops.interfaces.dto.Result;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi")
public class OpenApiDiscoveryController {

    private final NamespaceService namespaceService;
    private final EnvironmentService environmentService;

    public OpenApiDiscoveryController(NamespaceService namespaceService,
                                      EnvironmentService environmentService) {
        this.namespaceService = namespaceService;
        this.environmentService = environmentService;
    }

    @GetMapping("/namespaces")
    public Result<List<Namespace>> listNamespaces() {
        return Result.success(namespaceService.getNamespaces());
    }

    @GetMapping("/environments")
    public Result<List<EnvironmentDto>> listEnvironments() {
        List<EnvironmentDto> environments = environmentService.getEnvironments().stream()
                .map(EnvironmentDto::fromRedacted)
                .toList();
        return Result.success(environments);
    }
}
