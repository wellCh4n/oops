package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.shared.exception.BizException;

/**
 * How raw samples are combined when a requested range is too long to return point-for-point.
 *
 * <p>Both are kept because they answer different questions: {@link #AVG} shows what the pod normally consumes,
 * {@link #MAX} preserves the spikes that averaging would flatten away — which is usually the reason someone opened
 * the chart in the first place.
 */
public enum MetricAggregation {

    AVG,
    MAX;

    public static MetricAggregation parse(String value) {
        if (value == null || value.isBlank()) {
            return AVG;
        }
        return switch (value.trim().toLowerCase()) {
            case "max" -> MAX;
            case "avg" -> AVG;
            default -> throw new BizException("Unsupported aggregation: " + value);
        };
    }
}
