package com.github.wellch4n.oops.common.objects;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2023/1/29
 */
public class PageResult<T> {
    private List<T> data;
    private Long total;
    private Long current;
    private Long pageSize;

    public PageResult(List<T> data, Long total, Long current, Long pageSize) {
        this.data = data;
        this.total = total;
        this.current = current;
        this.pageSize = pageSize;
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getCurrent() {
        return current;
    }

    public void setCurrent(Long current) {
        this.current = current;
    }

    public Long getPageSize() {
        return pageSize;
    }

    public void setPageSize(Long pageSize) {
        this.pageSize = pageSize;
    }
}
