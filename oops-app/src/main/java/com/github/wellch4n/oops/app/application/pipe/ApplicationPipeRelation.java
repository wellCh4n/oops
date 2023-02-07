package com.github.wellch4n.oops.app.application.pipe;

import lombok.Data;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2023/2/8
 */

@Data
public class ApplicationPipeRelation {
    private Long appId;
    private List<ApplicationPipeVertex> vertex;
    private List<ApplicationPipeEdge> edges;
}
