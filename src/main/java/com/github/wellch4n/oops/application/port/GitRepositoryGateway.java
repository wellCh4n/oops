package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;

public interface GitRepositoryGateway {

    /**
     * Lists the remote branch names of a repository, using the git credentials of the environment.
     */
    List<String> listRemoteBranches(Environment environment, String repositoryUrl);
}
