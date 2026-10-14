package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.BuildVariable;
import java.util.List;

/**
 * The build configuration a pipeline was started with, captured when it is created (column
 * {@code build_config}). The application's build config can be edited the moment after, so the
 * build job and the build summary both read this copy — what the summary shows is what the job used.
 *
 * <p>The counterpart of {@link PublishConfig}: that one is what the operator chose when publishing,
 * this one the slice of the application's build config that applied to the pipeline's environment.
 * {@code null} on a pipeline that runs no build (rollback, image publish) or predates the column.
 */
public record PipelineBuildConfig(List<BuildVariable> buildVariables) {

    public PipelineBuildConfig {
        buildVariables = buildVariables == null ? List.of() : List.copyOf(buildVariables);
    }
}
