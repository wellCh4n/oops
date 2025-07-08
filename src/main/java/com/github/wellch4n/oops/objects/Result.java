package com.github.wellch4n.oops.objects;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */
public record Result<T>(
        boolean success,
        String message,
        T data
) {
    public static <T> Result<T> success(T data) {
        return new Result<T>(true, null, data);
    }

    public static <T> Result<T> failure(String message) {
        return new Result<T>(false, message, null);
    }
}
