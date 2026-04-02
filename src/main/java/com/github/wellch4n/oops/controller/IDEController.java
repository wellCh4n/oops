package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.IDEService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{application}/ide")
public class IDEController {

    private final IDEService ideService;

    public IDEController(IDEService  ideService) {
        this.ideService = ideService;
    }

    @PostMapping
    public Result<String> createIDE(@PathVariable String namespace, @PathVariable String application,
                                    @RequestParam String env) {
        return Result.success(ideService.create(namespace, application, env));
    }
}
