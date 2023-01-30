package com.github.wellch4n.oops.common.objects;

/**
 * @author wellCh4n
 * @date 2023/1/31
 */
public class PageParam {
    private Integer pageSize = 15;
    private Integer current = 1;

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getCurrent() {
        return current;
    }

    public void setCurrent(Integer current) {
        this.current = current;
    }
}
