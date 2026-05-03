package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.IdeConfigDto;
import com.github.wellch4n.oops.application.dto.CreateIdeCommand;
import com.github.wellch4n.oops.application.dto.IdeDto;
import java.util.List;

public interface IdeGateway {
    IdeConfigDto getDefaultIDEConfig(Environment environment);

    String create(String namespace,
                  String applicationName,
                  Environment environment,
                  Application application,
                  ApplicationBuildConfig applicationBuildConfig,
                  CreateIdeCommand request);

    void delete(Environment environment, String name);

    List<IdeDto> list(Environment environment, String applicationName);
}
