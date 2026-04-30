package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.interfaces.dto.NodeStatusResponse;
import java.util.List;

public interface ClusterNodeGateway {
    List<NodeStatusResponse> getNodes(Environment environment);
}
