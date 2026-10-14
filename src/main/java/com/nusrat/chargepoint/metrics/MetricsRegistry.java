package com.nusrat.chargepoint.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * A minimal metrics registry that exposes counters and gauges in
 * Prometheus's plain-text exposition format
 * (https://prometheus.io/docs/instrumenting/exposition_formats/), so a
 * real Prometheus instance can scrape this service's /metrics endpoint
 * without any translation layer.
 *
 * In a production Spring Boot service this would be Micrometer talking
 * to micrometer-registry-prometheus. This hand-written version keeps the
 * project dependency-free, but the
 * wire format it produces is the real Prometheus format, not a
 * simplified stand-in.
 */
public final class MetricsRegistry {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Double>> gauges = new ConcurrentHashMap<>();

    public void incrementCounter(String name, String... labelPairs) {
        counters.computeIfAbsent(key(name, labelPairs), k -> new LongAdder()).increment();
    }

    public void registerGauge(String name, Supplier<Double> valueSupplier) {
        gauges.put(name, valueSupplier);
    }

    private String key(String name, String... labelPairs) {
        if (labelPairs.length == 0) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append("{");
        for (int i = 0; i < labelPairs.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append(labelPairs[i]).append("=\"").append(labelPairs[i + 1]).append("\"");
        }
        return sb.append("}").toString();
    }

    public String scrape() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((key, value) ->
                sb.append(baseName(key)).append("_total").append(labelSuffix(key))
                        .append(' ').append(value.sum()).append('\n'));
        gauges.forEach((name, supplier) ->
                sb.append(name).append(' ').append(supplier.get()).append('\n'));
        return sb.toString();
    }

    private String baseName(String key) {
        int idx = key.indexOf('{');
        return idx == -1 ? key : key.substring(0, idx);
    }

    private String labelSuffix(String key) {
        int idx = key.indexOf('{');
        return idx == -1 ? "" : key.substring(idx);
    }
}
