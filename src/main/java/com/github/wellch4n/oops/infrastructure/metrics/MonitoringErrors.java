package com.github.wellch4n.oops.infrastructure.metrics;

/**
 * Error codes the charts recognise. Sent instead of prose so the browser can render a localised, actionable prompt.
 *
 * <p>The alert scan reads them too, to tell "this cluster has no monitoring" — which it must pass over quietly, once
 * a minute, forever — apart from a backend that is present but answering badly, which is worth a warning.
 */
public final class MonitoringErrors {

    /**
     * No monitoring backend to talk to at all — either none is installed where the convention expects it, or the
     * charts have been turned off by blanking the configured namespace. Both mean the same thing to a viewer, so
     * they share a code; the server log says which.
     */
    public static final String NOT_AVAILABLE = "MONITORING_NOT_AVAILABLE";

    private MonitoringErrors() {
    }
}
