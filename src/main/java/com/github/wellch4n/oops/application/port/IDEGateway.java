package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Application;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationBuildConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.interfaces.dto.IDEConfigResponse;
import com.github.wellch4n.oops.interfaces.dto.IDECreateRequest;
import com.github.wellch4n.oops.interfaces.dto.IDEResponse;
import java.util.List;

public interface IDEGateway {
    IDEConfigResponse getDefaultIDEConfig(Environment environment);

    String create(String namespace,
                  String applicationName,
                  Environment environment,
                  Application application,
                  ApplicationBuildConfig applicationBuildConfig,
                  IDECreateRequest request);

    void delete(Environment environment, String name);

    List<IDEResponse> list(Environment environment, String applicationName);
}
