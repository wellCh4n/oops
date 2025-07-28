package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.objects.ApplicationQueryRequest;
import com.github.wellch4n.oops.objects.PipelineQueryRequest;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class IndexService {

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;

    public IndexService(ApplicationRepository applicationRepository,
                        PipelineRepository pipelineRepository) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
    }

    public List<Pipeline> queryPipelines(PipelineQueryRequest pipelineQueryRequest) {
        return pipelineRepository.findAll((root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.isNotEmpty(pipelineQueryRequest.getNamespace())) {
                predicates.add(criteriaBuilder.equal(root.get("namespace"), pipelineQueryRequest.getNamespace()));
            }

            if (StringUtils.isNotEmpty(pipelineQueryRequest.getApplicationName())) {
                predicates.add(criteriaBuilder.like(root.get("applicationName"), "%" + pipelineQueryRequest.getApplicationName() + "%"));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        });
    }

    public List<Application> queryApplications(ApplicationQueryRequest applicationQueryRequest) {
        return applicationRepository.findAll((root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.isNotEmpty(applicationQueryRequest.getName())) {
                predicates.add(criteriaBuilder.like(root.get("name"), "%" + applicationQueryRequest.getName() + "%"));
            }

            if (StringUtils.isNotEmpty(applicationQueryRequest.getNamespace())) {
                predicates.add(criteriaBuilder.equal(root.get("namespace"), applicationQueryRequest.getNamespace()));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        });
    }
}
