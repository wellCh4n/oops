package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.NodeStatusResponse;
import java.util.List;

public interface ClusterNodeGateway {
    List<NodeStatusResponse> getNodes(Environment environment);
}
