package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.interfaces.dto.ConfigMapItem;
import com.github.wellch4n.oops.interfaces.dto.ConfigMapRequest;
import java.util.List;

public interface ConfigMapGateway {
    List<ConfigMapItem> getConfigMaps(Environment environment, String namespace, String applicationName);

    void updateConfigMap(Environment environment,
                         String namespace,
                         String applicationName,
                         List<ConfigMapRequest> configMaps);
}
