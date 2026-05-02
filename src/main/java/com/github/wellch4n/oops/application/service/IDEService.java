package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.IdeGateway;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.application.dto.IdeConfigDto;
import com.github.wellch4n.oops.application.dto.CreateIdeCommand;
import com.github.wellch4n.oops.application.dto.IdeDto;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "oops.ide", name = "enabled", havingValue = "true")
public class IdeService {

    private final EnvironmentService environmentService;
    private final ApplicationService applicationService;
    private final IdeGateway ideGateway;

    public IdeService(EnvironmentService environmentService,
                      ApplicationService applicationService,
                      IdeGateway ideGateway) {
        this.environmentService = environmentService;
        this.applicationService = applicationService;
        this.ideGateway = ideGateway;
    }

    public IdeConfigDto getDefaultIDEConfig(String env) {
        Environment environment = environmentService.getEnvironment(env);
        return ideGateway.getDefaultIDEConfig(environment);
    }

    public String create(String namespace, String applicationName, String env, CreateIdeCommand request) {
        Environment environment = environmentService.getEnvironment(env);
        if (environment == null) {
            return null;
        }

        Application application = applicationService.getApplication(namespace, applicationName);
        ApplicationBuildConfig applicationBuildConfig = application != null ? application.getBuildConfig() : null;
        ApplicationSourceType sourceType = applicationBuildConfig != null && applicationBuildConfig.getSourceType() != null
                ? applicationBuildConfig.getSourceType()
                : ApplicationSourceType.GIT;
        if (sourceType == ApplicationSourceType.ZIP) {
            throw new BizException("IDE is not supported for ZIP source applications");
        }
        return ideGateway.create(namespace, applicationName, environment, application, applicationBuildConfig, request);
    }

    public void delete(String name, String env) {
        Environment environment = environmentService.getEnvironment(env);
        ideGateway.delete(environment, name);
    }

    public List<IdeDto> list(String applicationName, String env) {
        Environment environment = environmentService.getEnvironment(env);
        return ideGateway.list(environment, applicationName);
    }
}
