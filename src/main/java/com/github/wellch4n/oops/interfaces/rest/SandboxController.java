package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.SandboxExecutionRequest;
import com.github.wellch4n.oops.application.dto.SandboxInstanceCreateRequest;
import com.github.wellch4n.oops.application.dto.SandboxInstanceExecRequest;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxExecutionResult;
import com.github.wellch4n.oops.application.service.SandboxExecutionService;
import com.github.wellch4n.oops.application.service.SandboxInstanceService;
import com.github.wellch4n.oops.application.service.SandboxRuntimeService;
import com.github.wellch4n.oops.application.service.SandboxRuntimeService.SandboxRuntimeView;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstance;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.dto.Result;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/sandbox", "/openapi/sandbox"})
public class SandboxController {

    private final SandboxExecutionService sandboxExecutionService;
    private final SandboxInstanceService sandboxInstanceService;
    private final SandboxRuntimeService sandboxRuntimeService;

    public SandboxController(SandboxExecutionService sandboxExecutionService,
                             SandboxInstanceService sandboxInstanceService,
                             SandboxRuntimeService sandboxRuntimeService) {
        this.sandboxExecutionService = sandboxExecutionService;
        this.sandboxInstanceService = sandboxInstanceService;
        this.sandboxRuntimeService = sandboxRuntimeService;
    }

    @GetMapping("/runtimes")
    public Result<List<SandboxRuntimeView>> listRuntimes() {
        return Result.success(sandboxRuntimeService.list());
    }

    @PostMapping("/executions")
    public Object execute(@RequestBody SandboxExecutionRequest request,
                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        boolean streaming = Boolean.TRUE.equals(request.stream());
        if (streaming) {
            return sandboxExecutionService.stream(request, callerUserId);
        }
        SandboxExecutionResult result = sandboxExecutionService.execute(request, callerUserId);
        return Result.success(result);
    }

    @PostMapping("/instances")
    public Result<SandboxInstance> create(@RequestBody SandboxInstanceCreateRequest request,
                                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        return Result.success(sandboxInstanceService.create(request, callerUserId));
    }

    @GetMapping("/instances")
    public Result<List<SandboxInstance>> list(@RequestParam(value = "environment", required = false) String environment,
                                              @RequestParam(value = "runtime", required = false) String runtime,
                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        return Result.success(sandboxInstanceService.list(callerUserId, environment, runtime));
    }

    @GetMapping("/instances/{id}")
    public Result<SandboxInstance> get(@PathVariable("id") String id,
                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        return Result.success(sandboxInstanceService.get(id, callerUserId));
    }

    @DeleteMapping("/instances/{id}")
    public Result<Void> delete(@PathVariable("id") String id,
                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        sandboxInstanceService.delete(id, callerUserId);
        return Result.success(null);
    }

    @PostMapping("/instances/{id}/exec")
    public Object exec(@PathVariable("id") String id,
                       @RequestBody SandboxInstanceExecRequest request,
                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        boolean streaming = Boolean.TRUE.equals(request.stream());
        if (streaming) {
            return sandboxInstanceService.streamExec(id, request, callerUserId);
        }
        SandboxExecutionResult result = sandboxInstanceService.exec(id, request, callerUserId);
        return Result.success(result);
    }
}
