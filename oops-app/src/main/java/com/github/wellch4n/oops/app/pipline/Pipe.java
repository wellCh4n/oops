package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public abstract class Pipe {

    public abstract String description();
    public abstract void execute(Application application);
}
