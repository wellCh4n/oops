package com.github.wellch4n.oops.app.application;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * @author wellCh4n
 * @date 2023/1/24
 */

@Data
@TableName(value = "oops_application")
public class Application implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 应用名
     */
    private String appName;

    /**
     * 应用描述
     */
    private String description;

    /**
     * 应用描述
     */
    private String namespace;
}
