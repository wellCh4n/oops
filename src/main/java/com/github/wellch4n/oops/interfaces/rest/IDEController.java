package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.IDEConfigResponse;
import com.github.wellch4n.oops.application.dto.IDECreateRequest;
import com.github.wellch4n.oops.application.dto.IDEResponse;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.IDEService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "oops.ide", name = "enabled", havingValue = "true")
@RequestMapping("/api/namespaces/{namespace}/applications/{application}/ides")
public class IDEController {

    private final IDEService ideService;

    public IDEController(IDEService  ideService) {
        this.ideService = ideService;
    }

    @GetMapping
    public Result<List<IDEResponse>> listIDEs(@PathVariable String application, @RequestParam String env) {
        return Result.success(ideService.list(application, env));
    }

    @DeleteMapping("/{name}")
    public Result<Void> deleteIDE(@PathVariable String name, @RequestParam String env) {
        ideService.delete(name, env);
        return Result.success(null);
    }

    @GetMapping("/config/default")
    public Result<IDEConfigResponse> getDefaultIDEConfig(@RequestParam String env) {
        return Result.success(ideService.getDefaultIDEConfig(env));
    }

    @PostMapping
    public Result<String> createIDE(@PathVariable String namespace, @PathVariable String application,
                                    @RequestParam String env, @RequestBody IDECreateRequest request) {
        return Result.success(ideService.create(namespace, application, env, request));
    }
}
