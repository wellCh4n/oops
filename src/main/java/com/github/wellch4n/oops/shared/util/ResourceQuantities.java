package com.github.wellch4n.oops.shared.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Kubernetes resource quantity strings ({@code 500m}, {@code 2}, {@code 512Mi}, {@code 1Gi}) into plain
 * numbers.
 *
 * <p>Fabric8 has {@code Quantity} for this, but the application layer is not allowed to import Fabric8 — and the
 * alert evaluator needs to turn a configured limit into a comparable number before it can build a PromQL threshold.
 *
 * <p>Every method returns {@code null} rather than throwing when the input is blank or malformed. The only caller
 * treats "no usable limit" and "no limit configured" identically: both mean the application cannot be alerted on,
 * which is the agreed behaviour when an application has no resource limits set.
 */
public final class ResourceQuantities {

    private static final Pattern QUANTITY = Pattern.compile("^(\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*([a-zA-Z]*)$");

    private static final Map<String, Double> MULTIPLIERS = Map.ofEntries(
            Map.entry("", 1d),
            Map.entry("m", 1e-3),
            Map.entry("k", 1e3),
            Map.entry("K", 1e3),
            Map.entry("M", 1e6),
            Map.entry("G", 1e9),
            Map.entry("T", 1e12),
            Map.entry("P", 1e15),
            Map.entry("E", 1e18),
            Map.entry("Ki", 1024d),
            Map.entry("Mi", 1024d * 1024),
            Map.entry("Gi", 1024d * 1024 * 1024),
            Map.entry("Ti", 1024d * 1024 * 1024 * 1024),
            Map.entry("Pi", 1024d * 1024 * 1024 * 1024 * 1024),
            Map.entry("Ei", 1024d * 1024 * 1024 * 1024 * 1024 * 1024));

    private ResourceQuantities() {
    }

    /** {@code 500m} → 500, {@code 2} → 2000. Null when unparseable. */
    public static Long toMillicores(String quantity) {
        Double cores = parse(quantity);
        return cores == null ? null : Math.round(cores * 1000d);
    }

    /** {@code 512Mi} → 536870912. Null when unparseable. */
    public static Long toBytes(String quantity) {
        Double bytes = parse(quantity);
        return bytes == null ? null : Math.round(bytes);
    }

    private static Double parse(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return null;
        }
        Matcher matcher = QUANTITY.matcher(quantity.trim());
        if (!matcher.matches()) {
            return null;
        }
        Double multiplier = MULTIPLIERS.get(matcher.group(2));
        if (multiplier == null) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1)) * multiplier;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
