package com.jtac.shared.web;

import java.util.List;
import java.util.function.Function;

/** A page of results. {@code total} is exact; pages are zero-based. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static final int MAX_SIZE = 200;

    public static int clampSize(Integer size, int defaultSize) {
        if (size == null || size < 1) {
            return defaultSize;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static int clampPage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(items.stream().map(mapper).toList(), page, size, total);
    }
}
