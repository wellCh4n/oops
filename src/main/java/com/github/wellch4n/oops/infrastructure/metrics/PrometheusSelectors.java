package com.github.wellch4n.oops.infrastructure.metrics;

import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.regex.Pattern;

/**
 * Builds the PromQL label matcher that selects one application's containers, shared by the usage charts and the
 * resource alerts so both always read the same series.
 *
 * <p>Pods are selected by name rather than by the {@code oops.app.name} label the deploy path stamps on them: kubelet
 * and cAdvisor metrics carry no pod labels, and joining against {@code kube_pod_labels} would force every user to also
 * run kube-state-metrics. Applications deploy as StatefulSets named after the application, so their pods are always
 * {@code {app}-0}, {@code {app}-1}, … — and PromQL matchers are fully anchored, so {@code foo-[0-9]+} cannot
 * accidentally match {@code foo-bar-0}.
 */
final class PrometheusSelectors {

    /**
     * Namespaces and application names are interpolated into PromQL label matchers. Anything outside the Kubernetes
     * name charset is rejected rather than escaped: a name containing a quote could otherwise close the matcher and
     * read another namespace's series.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private PrometheusSelectors() {
    }

    /**
     * Selects every container of the application's pods in one namespace.
     *
     * <p>{@code container!=""} drops the pod-level rollup series and {@code container!="POD"} drops the pause
     * container; without both, a pod's usage would be counted two or three times over. Both are cAdvisor traits —
     * kubelet's {@code /metrics/resource} endpoint emits neither — so on a {@code /metrics/resource}-only backend
     * they simply match everything, which is why they are safe to apply unconditionally.
     */
    static String podSelector(String namespace, String applicationName) {
        requireSafeName(namespace, "namespace");
        requireSafeName(applicationName, "application name");
        return "namespace=\"" + namespace + "\","
                + "pod=~\"" + applicationName + "-[0-9]+\","
                + "container!=\"\",container!=\"POD\"";
    }

    static void requireSafeName(String value, String what) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new BizException("Invalid " + what + ": " + value);
        }
    }
}
