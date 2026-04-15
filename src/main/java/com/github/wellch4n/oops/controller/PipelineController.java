package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.objects.Page;
import com.github.wellch4n.oops.objects.PipelineResponse;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.PipelineService;
import org.springframework.web.bind.annotation.*;

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
    public Result<Page<PipelineResponse>> getPipelines(@PathVariable String namespace,
                                                       @PathVariable String name,
                                                       @RequestParam(required = false) String environment,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.success(pipelineService.getPipelines(namespace, name, environment, page, size));
    }

    @GetMapping("/{id}")
    public Result<PipelineResponse> getPipeline(@PathVariable String namespace,
                                                @PathVariable String name,
                                                @PathVariable String id) {
        return Result.success(pipelineService.getPipelineDetail(namespace, name, id));
    }

//    @GetMapping(value = "/{id}/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public SseEmitter watchPipeline(@PathVariable String namespace,
//                                    @PathVariable String name,
//                                    @PathVariable String id) {
//        return pipelineService.watchPipeline(namespace, name, id);
//    }

    @PutMapping("/{id}/stop")
    public Result<Boolean> stopPipeline(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String id) {
        return Result.success(pipelineService.stopPipeline(namespace, name, id));
    }

    @PutMapping("/{id}/deploy")
    public Result<Boolean> deployPipeline(@PathVariable String namespace,
                                          @PathVariable String name,
                                          @PathVariable String id) {
        return Result.success(pipelineService.deployPipeline(namespace, name, id));
    }
}
