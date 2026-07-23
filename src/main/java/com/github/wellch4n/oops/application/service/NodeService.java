package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ClusterNodeGateway;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.NodeStatusView;
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

    public List<NodeStatusView> getNodes(String environmentName) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new IllegalArgumentException("Environment not found: " + environmentName);
        }
        return clusterNodeGateway.getNodes(environment);
    }

    public void setSchedulable(String environmentName, String nodeName, boolean schedulable) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new IllegalArgumentException("Environment not found: " + environmentName);
        }
        clusterNodeGateway.setSchedulable(environment, nodeName, schedulable);
    }
}
