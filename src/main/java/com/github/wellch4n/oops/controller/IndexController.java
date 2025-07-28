package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.objects.ApplicationQueryRequest;
import com.github.wellch4n.oops.objects.PipelineQueryRequest;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.IndexService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author  wellCh4n
 * @date    2025/7/13
 */

@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final IndexService indexService;

    public IndexController(IndexService indexService,
                           ApplicationRepository applicationRepository,
                           PipelineRepository pipelineRepository) {
        this.indexService = indexService;
    }

    @PostMapping("/pipelines")
    public Result<List<Pipeline>> queryPipelines(@RequestBody PipelineQueryRequest pipelineQueryRequest) {
        return Result.success(indexService.queryPipelines(pipelineQueryRequest));
    }

    @PostMapping("/applications")
    public Result<List<Application>> queryApplication(@RequestBody ApplicationQueryRequest applicationQueryRequest) {
        return Result.success(indexService.queryApplications(applicationQueryRequest));
    }
}
