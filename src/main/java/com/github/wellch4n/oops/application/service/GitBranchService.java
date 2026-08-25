package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.GitBranchView;
import com.github.wellch4n.oops.application.port.GitRepositoryGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class GitBranchService {

    private final ApplicationRepository applicationRepository;
    private final EnvironmentService environmentService;
    private final GitRepositoryGateway gitRepositoryGateway;

    public GitBranchService(ApplicationRepository applicationRepository,
                            EnvironmentService environmentService,
                            GitRepositoryGateway gitRepositoryGateway) {
        this.applicationRepository = applicationRepository;
        this.environmentService = environmentService;
        this.gitRepositoryGateway = gitRepositoryGateway;
    }

    /**
     * Remote branches of the application repository with their tip commits, read with the git credentials of the given
     * environment. Returns an empty list when the application does not build from git, so the UI can
     * fall back to a plain text input.
     */
    public List<GitBranchView> listBranches(String namespace, String applicationName, String environmentName) {
        Application application = applicationRepository.findAggregate(namespace, applicationName);
        if (application == null) {
            throw new BizException("Application not found");
        }

        ApplicationBuildConfig buildConfig = application.getBuildConfig();
        if (buildConfig == null || buildConfig.getSourceType() != ApplicationSourceType.GIT) {
            return List.of();
        }
        String repository = buildConfig.repository();
        if (StringUtils.isBlank(repository)) {
            return List.of();
        }

        Environment environment = environmentService.getEnvironment(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        return gitRepositoryGateway.listRemoteBranches(environment, repository);
    }
}
