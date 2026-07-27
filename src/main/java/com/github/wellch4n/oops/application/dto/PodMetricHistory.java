package com.github.wellch4n.oops.application.dto;

import java.util.List;

/**
 * The usage history of one application, split into one series per pod. Pods that have already been deleted stay in
 * the response until their samples age out of the retention window, so a rollout stays visible on the chart.
 *
 * @param intervalSeconds the spacing between the returned points, after downsampling
 * @param aggregation     how samples were combined into each returned point ({@code avg} or {@code max})
 * @param series          one entry per pod, ordered by pod name
 */
public record PodMetricHistory(int intervalSeconds, String aggregation, List<Series> series) {

    /**
     * @param podName the pod name (e.g. {@code my-app-0})
     * @param points  samples ordered oldest first
     */
    public record Series(String podName, List<PodMetricPoint> points) {
    }
}
