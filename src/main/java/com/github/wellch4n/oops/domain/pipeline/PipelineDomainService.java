package com.github.wellch4n.oops.domain.pipeline;

import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.exception.BizException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PipelineDomainService {

    public void ensureNoInFlightDeployment(PipelineRepository pipelineRepository,
                                           String namespace, String applicationName) {
        if (pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                namespace, applicationName, List.of(PipelineStatus.RUNNING, PipelineStatus.DEPLOYING))) {
            throw new BizException("Application is being deployed");
        }
    }
}
