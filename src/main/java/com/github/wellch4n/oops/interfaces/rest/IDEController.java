package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.IdeConfigDto;
import com.github.wellch4n.oops.application.dto.CreateIdeCommand;
import com.github.wellch4n.oops.application.dto.IdeDto;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.IdeService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "oops.ide", name = "enabled", havingValue = "true")
@RequestMapping("/api/namespaces/{namespace}/applications/{application}/ides")
public class IdeController {

    private final IdeService ideService;

    public IdeController(IdeService  ideService) {
        this.ideService = ideService;
    }

    @GetMapping
    public Result<List<IdeDto>> listIDEs(@PathVariable String application, @RequestParam String env) {
        return Result.success(ideService.list(application, env));
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> deleteIDE(@PathVariable String namespace,
                                  @PathVariable String application,
                                  @PathVariable String name,
                                  @RequestParam String env) {
        ideService.delete(name, env);
        return Result.success(null);
    }

    @GetMapping("/config/default")
    public Result<IdeConfigDto> getDefaultIDEConfig(@RequestParam String env) {
        return Result.success(ideService.getDefaultIDEConfig(env));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Result<String> createIDE(@PathVariable String namespace, @PathVariable String application,
                                    @RequestParam String env, @RequestBody CreateIdeCommand request) {
        return Result.success(ideService.create(namespace, application, env, request));
    }
}
