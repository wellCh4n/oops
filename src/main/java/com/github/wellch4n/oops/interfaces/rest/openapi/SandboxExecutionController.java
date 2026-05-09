package com.github.wellch4n.oops.interfaces.rest.openapi;

import com.github.wellch4n.oops.application.dto.SandboxExecutionRequest;
import com.github.wellch4n.oops.application.service.SandboxExecutionService;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/openapi/sandbox")
public class SandboxExecutionController {

    private final SandboxExecutionService sandboxExecutionService;

    public SandboxExecutionController(SandboxExecutionService sandboxExecutionService) {
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @PostMapping(value = "/executions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(@RequestBody SandboxExecutionRequest request,
                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        String callerUserId = principal != null ? principal.userId() : null;
        return sandboxExecutionService.execute(request, callerUserId);
    }
}
