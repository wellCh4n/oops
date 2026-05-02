package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.NodeStatusView;
import java.util.List;

public interface ClusterNodeGateway {
    List<NodeStatusView> getNodes(Environment environment);
}
