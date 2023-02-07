package com.github.wellch4n.oops.app.application.pipe;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author wellCh4n
 * @date 2023/2/8
 */

@Data
@TableName(value = "oops_application_pipe_edge", autoResultMap = true)
public class ApplicationPipeEdge {

    private Long id;
    private String startVertex;
    private String endVertex;
    private Long appId;
}
