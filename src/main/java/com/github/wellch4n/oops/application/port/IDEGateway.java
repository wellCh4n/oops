package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
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
