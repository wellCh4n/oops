package com.github.wellch4n.oops.app.pipline;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */
public class PipeParam {
    private String name;
    private Class<?> type;
    private Object value;

    public PipeParam(String name, Class<?> type, Object value) {
        this.name = name;
        this.value = value;
        this.type = type;
    }

    public PipeParam(String name, Class<?> type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Class<?> getType() {
        return type;
    }

    public void setType(Class<?> type) {
        this.type = type;
    }
}
