package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.service.ServiceAccountService;
import com.github.wellch4n.oops.interfaces.dto.Result;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/namespaces/{namespace}/service-accounts")
public class ServiceAccountController {

    private final ServiceAccountService serviceAccountService;

    public ServiceAccountController(ServiceAccountService serviceAccountService) {
        this.serviceAccountService = serviceAccountService;
    }

    @GetMapping
    public Result<List<String>> listServiceAccounts(@PathVariable String namespace,
                                                    @RequestParam String env) {
        return Result.success(serviceAccountService.listServiceAccountNames(namespace, env));
    }
}
