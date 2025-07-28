package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.PipelineService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{name}/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping
    public Result<List<Pipeline>> getPipelines(@PathVariable String namespace,
                                               @PathVariable String name) {
        return Result.success(pipelineService.getPipelines(namespace, name));
    }

    @GetMapping("/{id}")
    public Result<Pipeline> getPipeline(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String id) {
        return Result.success(pipelineService.getPipeline(namespace, name, id));
    }

    @GetMapping(value = "/{id}/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchPipeline(@PathVariable String namespace,
                                    @PathVariable String name,
                                    @PathVariable String id) {
        return pipelineService.watchPipeline(namespace, name, id);
    }

    @PutMapping("/{id}/stop")
    public Result<Boolean> stopPipeline(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String id) {
        return Result.success(pipelineService.stopPipeline(namespace, name, id));
    }
}
