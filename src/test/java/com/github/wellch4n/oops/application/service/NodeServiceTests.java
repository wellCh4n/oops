package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.ClusterNodeGateway;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.application.dto.NodeStatusView;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeServiceTests {

    private EnvironmentRepository environmentRepository;
    private ClusterNodeGateway clusterNodeGateway;
    private NodeService nodeService;

    @BeforeEach
    void setUp() {
        environmentRepository = mock(EnvironmentRepository.class);
        clusterNodeGateway = mock(ClusterNodeGateway.class);
        nodeService = new NodeService(environmentRepository, clusterNodeGateway);
    }

    @Test
    void getNodesThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> nodeService.getNodes("missing"));
    }

    @Test
    void getNodesDelegatesToGateway() {
        Environment env = new Environment();
        when(environmentRepository.findFirstByName("prod")).thenReturn(env);
        when(clusterNodeGateway.getNodes(env)).thenReturn(List.of(new NodeStatusView()));

        List<NodeStatusView> nodes = nodeService.getNodes("prod");
        assertEquals(1, nodes.size());
    }
}
