package com.github.wellch4n.oops.domain.shared;

/**
 * Distinguishes how a pipeline was triggered.
 *
 * <ul>
 *   <li>{@link #BUILD} — a normal build-and-deploy pipeline.</li>
 *   <li>{@link #ROLLBACK} — a rollback that reuses a historic artifact and skips the build phase.</li>
 * </ul>
 */
public enum PipelineTriggerType {
    BUILD,
    ROLLBACK
}
