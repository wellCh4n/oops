package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.application.dto.ApplicationQuery;
import com.github.wellch4n.oops.application.dto.PipelineQuery;
import java.util.List;
import org.springframework.stereotype.Service;

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

    public List<Pipeline> queryPipelines(PipelineQuery pipelineQueryRequest) {
        return pipelineRepository.query(pipelineQueryRequest.getNamespace(), pipelineQueryRequest.getApplicationName());
    }

    public List<Application> queryApplications(ApplicationQuery applicationQueryRequest) {
        return applicationRepository.query(applicationQueryRequest.getNamespace(), applicationQueryRequest.getName());
    }
}
