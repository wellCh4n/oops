package com.github.wellch4n.oops.objects;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2026/4/9
 */
public record Page<T>(
        long total,
        List<T> data,
        int size,
        int totalPages
) {
    public static <T> Page<T> of(long total, List<T> data, int size) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new Page<>(total, data, size, totalPages);
    }

    public static <T> Page<T> of(org.springframework.data.domain.Page<T> springPage) {
        return new Page<>(springPage.getTotalElements(), springPage.getContent(), springPage.getSize(), springPage.getTotalPages());
    }
}
