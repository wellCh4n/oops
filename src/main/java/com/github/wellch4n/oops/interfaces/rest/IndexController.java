package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Application;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRepository;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.PipelineRepository;
import com.github.wellch4n.oops.interfaces.dto.ApplicationQueryRequest;
import com.github.wellch4n.oops.interfaces.dto.PipelineQueryRequest;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.IndexService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

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
