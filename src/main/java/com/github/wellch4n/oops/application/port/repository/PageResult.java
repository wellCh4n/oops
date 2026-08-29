package com.github.wellch4n.oops.application.port.repository;

import java.util.List;

public record PageResult<T>(
        long totalElements,
        List<T> content,
        int size,
        int totalPages
) {
}
