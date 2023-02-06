package com.github.wellch4n.oops.common.core;

import lombok.Data;

import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/2/6
 */

@Data
public class PipeStruct {
    private Set<PipeInput> inputs;
    private String title;
    private String clazzName;
}
