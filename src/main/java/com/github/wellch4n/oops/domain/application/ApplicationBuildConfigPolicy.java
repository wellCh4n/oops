package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import com.github.wellch4n.oops.shared.exception.BizException;

public class ApplicationBuildConfigPolicy {

    public ApplicationSourceType normalizeSourceType(ApplicationSourceType sourceType) {
        return sourceType != null ? sourceType : ApplicationSourceType.GIT;
    }

    public void validate(ApplicationSourceType sourceType,
                         String repository,
                         DockerFileType dockerFileType,
                         String dockerFileContent) {
        ApplicationSourceType normalized = normalizeSourceType(sourceType);
        if (normalized == ApplicationSourceType.GIT && isBlank(repository)) {
            throw new BizException("Repository is required when source type is GIT");
        }
        if (dockerFileType == DockerFileType.USER && isBlank(dockerFileContent)) {
            throw new BizException("Dockerfile content is required when type is USER");
        }
    }

    public String normalizeRepository(ApplicationSourceType sourceType, String repository) {
        return normalizeSourceType(sourceType) == ApplicationSourceType.GIT ? repository : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
