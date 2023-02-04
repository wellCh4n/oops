package com.github.wellch4n.oops.common.core;

import lombok.Data;

/**
 * @author wellCh4n
 * @date 2023/2/5
 */

@Data
public class PipeInput {
    private String name;
    private String description;
    private Class<?> clazz;

    public PipeInput(String name, String description, Class<?> clazz) {
        this.name = name;
        this.description = description;
        this.clazz = clazz;
    }
}
