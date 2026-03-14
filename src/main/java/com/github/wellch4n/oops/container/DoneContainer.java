package com.github.wellch4n.oops.container;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class DoneContainer extends BaseContainer {

    public DoneContainer() {
        Container container = new ContainerBuilder()
                .withName("done")
                .withImage("busybox:1.36.1")
                .withCommand("sh", "-c", "echo done!")
                .build();

        // 映射属性
        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setCommand(container.getCommand());
    }
}