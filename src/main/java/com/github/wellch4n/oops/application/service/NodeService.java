package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ClusterNodeGateway;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.NodeStatusResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NodeService {

    private final EnvironmentRepository environmentRepository;
    private final ClusterNodeGateway clusterNodeGateway;

    public NodeService(EnvironmentRepository environmentRepository,
                       ClusterNodeGateway clusterNodeGateway) {
        this.environmentRepository = environmentRepository;
        this.clusterNodeGateway = clusterNodeGateway;
    }

    public List<NodeStatusResponse> getNodes(String environmentName) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new IllegalArgumentException("Environment not found: " + environmentName);
        }

        try {
            return clusterNodeGateway.getNodes(environment);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get nodes: " + e.getMessage(), e);
        }
    }
}
