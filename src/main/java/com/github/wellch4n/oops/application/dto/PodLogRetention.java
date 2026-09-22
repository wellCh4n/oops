package com.github.wellch4n.oops.application.dto;

/**
 * How much of a container's log the node keeps, read from the kubelet of the node the pod runs on:
 * the size one log file may reach before rotation and how many rotated files are kept. Both are
 * null when the kubelet's configuration could not be read (the environment's token lacks
 * {@code nodes/proxy}, or the node is unreachable).
 */
public record PodLogRetention(String maxFileSize, Integer maxFiles) {
}
