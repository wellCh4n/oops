package com.github.wellch4n.oops.domain.application;

import org.apache.commons.lang3.StringUtils;

/**
 * Application scheduling priority tier. Each tier maps to a Kubernetes PriorityClass applied to the
 * StatefulSet pod template so the scheduler favors — and can preempt lower-tier pods for — higher
 * tiers when cluster resources are tight.
 *
 * <p>{@code NORMAL} intentionally maps to no PriorityClass: existing applications keep the cluster
 * default priority and deploys never depend on a PriorityClass object existing. {@code HIGH} and
 * {@code LOW} reference named PriorityClasses ({@code oops-high-priority} / {@code oops-low-priority})
 * that the deploy pipeline auto-creates with {@link #defaultValue()} when absent — an existing object
 * (e.g. one an administrator pre-created with a different value) is left untouched.
 */
public enum ApplicationPriority {
    HIGH("oops-high-priority", 1_000_000),
    NORMAL(null, 0),
    LOW("oops-low-priority", -1_000_000);

    private final String priorityClassName;
    private final int defaultValue;

    ApplicationPriority(String priorityClassName, int defaultValue) {
        this.priorityClassName = priorityClassName;
        this.defaultValue = defaultValue;
    }

    public String priorityClassName() {
        return priorityClassName;
    }

    /** The numeric priority stamped onto pods, used when this tier's PriorityClass is auto-created. */
    public int defaultValue() {
        return defaultValue;
    }

    /** Parse a stored value, defaulting unknown or blank input to {@link #NORMAL}. */
    public static ApplicationPriority fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return NORMAL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return NORMAL;
        }
    }

    /** Resolve a stored value directly to its PriorityClass name (null for the normal tier). */
    public static String priorityClassNameOf(String value) {
        return fromValue(value).priorityClassName();
    }
}
