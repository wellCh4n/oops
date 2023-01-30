package com.github.wellch4n.oops.app.application;

import com.github.wellch4n.oops.common.objects.PageParam;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */
public class ApplicationPageParam extends PageParam {
    private String appName;
    private String namespace;

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
