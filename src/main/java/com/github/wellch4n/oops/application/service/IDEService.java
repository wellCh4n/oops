package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.IDEGateway;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.application.dto.IDEConfigResponse;
import com.github.wellch4n.oops.application.dto.IDECreateRequest;
import com.github.wellch4n.oops.application.dto.IDEResponse;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "oops.ide", name = "enabled", havingValue = "true")
public class IDEService {

    private final EnvironmentService environmentService;
    private final ApplicationService applicationService;
    private final IDEGateway ideGateway;

    public IDEService(EnvironmentService environmentService,
                      ApplicationService applicationService,
                      IDEGateway ideGateway) {
        this.environmentService = environmentService;
        this.applicationService = applicationService;
        this.ideGateway = ideGateway;
    }

    public IDEConfigResponse getDefaultIDEConfig(String env) {
        Environment environment = environmentService.getEnvironment(env);
        return ideGateway.getDefaultIDEConfig(environment);
    }

    public String create(String namespace, String applicationName, String env, IDECreateRequest request) {
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

    public List<IDEResponse> list(String applicationName, String env) {
        Environment environment = environmentService.getEnvironment(env);
        return ideGateway.list(environment, applicationName);
    }
}
