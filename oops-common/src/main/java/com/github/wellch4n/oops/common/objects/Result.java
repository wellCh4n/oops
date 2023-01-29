package com.github.wellch4n.oops.common.objects;

/**
 * @author wellCh4n
 * @date 2023/1/29
 */
public class Result<T> {
    private T data;
    private boolean success;
    private String message;

    public static <T> Result<T> success(T data) {
        return new Result<>(data, true, null);
    }

    public Result(T data, boolean success, String message) {
        this.data = data;
        this.success = success;
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
