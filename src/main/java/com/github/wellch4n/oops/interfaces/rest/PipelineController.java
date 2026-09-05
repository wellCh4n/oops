package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.application.dto.PipelineDto;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.interfaces.sse.SseEventStream;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping({
        "/api/namespaces/{namespace}/applications/{name}/pipelines",
        "/openapi/namespaces/{namespace}/applications/{name}/pipelines"
})
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /**
     * Both {@code namespace} and {@code name} accept {@code all} as a wildcard, so the pipeline
     * list page reads the whole scope through this same endpoint. {@code mine} keeps only the
     * pipelines the caller triggered, the counterpart of {@code ownerOnly} on the application list.
     */
    @GetMapping
    public Result<Page<PipelineDto>> getPipelines(@PathVariable String namespace,
                                                       @PathVariable String name,
                                                       @RequestParam(required = false) String environment,
                                                       @RequestParam(defaultValue = "false") boolean mine,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size,
                                                       Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        String operatorId = mine ? principal.userId() : null;
        return Result.success(pipelineService.getPipelines(namespace, name, environment, operatorId, page, size));
    }

    @GetMapping("/{id}")
    public Result<PipelineDto> getPipeline(@PathVariable String namespace,
                                                @PathVariable String name,
                                                @PathVariable String id) {
        return Result.success(pipelineService.getPipelineDetail(namespace, name, id));
    }

    /**
     * Server-sent {@code steps} / {@code status} events for the build until its pod finishes. Cheap
     * enough to hold open for the whole build: it carries one snapshot per container transition, not
     * a single line of log.
     */
    @GetMapping("/{id}/steps/watch")
    public SseEmitter watchPipelineSteps(@PathVariable String namespace,
                                         @PathVariable String name,
                                         @PathVariable String id) {
        SseEventStream stream = new SseEventStream();
        stream.attach(pipelineService.watchPipelineSteps(namespace, name, id, stream));
        return stream.emitter();
    }

    /**
     * Server-sent {@code log} batches for one build step. A finished step replays and ends at once;
     * a running one is followed until it terminates. The browser's own reconnect sends the last
     * event id back as {@code Last-Event-ID}, and the stream resumes after that line.
     */
    @GetMapping("/{id}/log")
    public SseEmitter streamPipelineStepLog(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @PathVariable String id,
                                            @RequestParam String container,
                                            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEventStream stream = new SseEventStream();
        stream.attach(pipelineService.streamPipelineStepLog(namespace, name, id, container, lastEventId, stream));
        return stream.emitter();
    }

    @PutMapping("/{id}/stop")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> stopPipeline(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String id,
                                        Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(pipelineService.stopPipeline(namespace, name, id, principal.userId()));
    }

    @PutMapping("/{id}/deploy")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> deployPipeline(@PathVariable String namespace,
                                          @PathVariable String name,
                                          @PathVariable String id,
                                          Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(pipelineService.deployPipeline(namespace, name, id, principal.userId()));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("isAuthenticated()")
    public Result<String> rollbackPipeline(@PathVariable String namespace,
                                           @PathVariable String name,
                                           @PathVariable String id,
                                           Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(pipelineService.rollback(namespace, name, id, principal.userId()));
    }
}
