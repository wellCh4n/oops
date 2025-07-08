package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.config.KubernetesContext;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces")
public class NamespaceController {

    @GetMapping
    public Result<Set<String>> getNamespaces() {
        try {
            V1NamespaceList v1NamespaceList = KubernetesContext.getApi().listNamespace().execute();
            Set<String> namespaces = v1NamespaceList.getItems().stream()
                    .map(namespace -> namespace.getMetadata().getName())
                    .collect(Collectors.toSet());
            return Result.success(namespaces);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }
}
