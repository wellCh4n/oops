package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.GitBranchView;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;

public interface GitRepositoryGateway {

    /**
     * Lists the remote branches of a repository with their latest commit, using the git credentials of
     * the environment.
     */
    List<GitBranchView> listRemoteBranches(Environment environment, String repositoryUrl);
}
