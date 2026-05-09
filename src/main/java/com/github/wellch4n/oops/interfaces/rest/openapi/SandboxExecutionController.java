package com.github.wellch4n.oops.interfaces.rest.openapi;

import com.github.wellch4n.oops.application.dto.SandboxExecutionRequest;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxExecutionResult;
import com.github.wellch4n.oops.application.service.SandboxExecutionService;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.dto.Result;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/sandbox")
public class SandboxExecutionController {

    private final SandboxExecutionService sandboxExecutionService;

    public SandboxExecutionController(SandboxExecutionService sandboxExecutionService) {
        this.sandboxExecutionService = sandboxExecutionService;
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
}
