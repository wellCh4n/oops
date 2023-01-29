package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;

import java.util.LinkedList;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public class Pipeline extends LinkedList<Pipe> {
    public void execute(Application application) {
        for (Pipe pipe : this) {
            pipe.execute(application);
        }
    }

    public Set<String> description() {
        return this.stream().map(Pipe::description).collect(Collectors.toSet());
    }
}
