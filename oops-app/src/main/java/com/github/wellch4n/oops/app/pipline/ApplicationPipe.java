package com.github.wellch4n.oops.app.pipline;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.wellch4n.oops.app.system.MapTypeHandler;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */

@TableName(value = "oops_application_pipe")
public class ApplicationPipe {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long appId;
    private String pipeClass;
    private Integer order;

    @TableField(typeHandler = MapTypeHandler.class)
    private Map<String, Object> params;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public String getPipeClass() {
        return pipeClass;
    }

    public void setPipeClass(String pipeClass) {
        this.pipeClass = pipeClass;
    }

    public Integer getOrder() {
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
