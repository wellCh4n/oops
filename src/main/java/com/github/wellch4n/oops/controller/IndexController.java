package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.objects.PipelineQuery;
import com.github.wellch4n.oops.objects.Result;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * @author  wellCh4n
 * @date    2025/7/13
 */

@RestController
@RequestMapping("/api/index")
public class IndexController {

    public final PipelineRepository pipelineRepository;

    public IndexController(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    @PostMapping("/pipelines")
    public Result<List<Pipeline>> queryPipeline(@RequestBody PipelineQuery pipelineQuery) {
        List<Pipeline> pipelines = pipelineRepository.findAll((root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.isNotEmpty(pipelineQuery.getNamespace())) {
                predicates.add(criteriaBuilder.equal(root.get("namespace"), pipelineQuery.getNamespace()));
            }

            if (StringUtils.isNotEmpty(pipelineQuery.getApplicationName())) {
                predicates.add(criteriaBuilder.equal(root.get("applicationName"), pipelineQuery.getApplicationName()));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        });
        return Result.success(pipelines);
    }
}
