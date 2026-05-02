package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.domain.namespace.Namespace;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.NamespaceService;
import com.github.wellch4n.oops.shared.util.ResourceNameChecker;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces")
public class NamespaceController {

    private final NamespaceService namespaceService;

    public NamespaceController(NamespaceService namespaceService) {
        this.namespaceService = namespaceService;
    }

    @GetMapping
    public Result<List<Namespace>> getNamespaces() {
        return Result.success(namespaceService.getNamespaces());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> createNamespace(@RequestBody Namespace namespace) {
        ResourceNameChecker.check(namespace.getName());
        namespaceService.createNamespace(namespace.getName(), namespace.getDescription());
        return Result.success(true);
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> updateNamespace(@RequestBody Namespace namespace) {
        namespaceService.updateNamespace(namespace.getName(), namespace.getDescription());
        return Result.success(true);
    }
}
