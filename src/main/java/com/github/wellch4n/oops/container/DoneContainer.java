package com.github.wellch4n.oops.container;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class DoneContainer extends BaseContainer {

    public DoneContainer() {
        this.name("done")
                .image("busybox:1.36.1")
                .command(List.of("sh","-c","echo done!"));
    }
}
