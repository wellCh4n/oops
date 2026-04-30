package com.github.wellch4n.oops.domain.shared;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */
public enum PipelineStatus {
    INITIALIZED, RUNNING, BUILD_SUCCEEDED, DEPLOYING,
    STOPPED,
    SUCCEEDED, ERROR
}
