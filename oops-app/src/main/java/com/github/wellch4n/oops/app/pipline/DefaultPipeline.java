package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */
public class DefaultPipeline {
    public static Pipeline create(Application application) {
        Pipeline pipeline = new Pipeline();

        pipeline.add(new GitPipe());
        pipeline.add(new BuildPipe());

        return pipeline;
    }
}
