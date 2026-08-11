package com.healthplatform.common.dto;

import java.util.List;

/** Standard paginated list wrapper returned by list endpoints. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
