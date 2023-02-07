package com.github.wellch4n.oops.app.application.pipe;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.wellch4n.oops.app.system.MapTypeHandler;
import com.github.wellch4n.oops.common.core.Description;
import lombok.Data;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/2/7
 */

@Data
@TableName(value = "oops_application_pipe_vertex", autoResultMap = true)
public class ApplicationPipeVertex {
    @TableId(value = "id")
    private String id;

    private Long appId;

    private String pipeClass;

    @TableField(value = "params", typeHandler = MapTypeHandler.class)
    private Map<String, Object> params;

    @TableField(exist = false)
    private String pipeName;

    public String getPipeName() {
        try {
            Class<?> pipClazz = Class.forName(pipeClass);
            Description annotation = pipClazz.getAnnotation(Description.class);
            return annotation.title();
        } catch (Exception e) {
            return null;
        }
    }

    public void setPipeClass(String pipeClass) {
        this.pipeClass = pipeClass;
        try {
            Class<?> pipClazz = Class.forName(pipeClass);
            Description annotation = pipClazz.getAnnotation(Description.class);
            this.pipeName = annotation.title();
        } catch (Exception ignored) {}
    }

    public void setPipeName(String pipeName) {
        this.pipeName = pipeName;
    }
}
