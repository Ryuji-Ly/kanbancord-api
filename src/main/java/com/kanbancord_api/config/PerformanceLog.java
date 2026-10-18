package com.kanbancord_api.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Every so often, one log line on how the API is doing: requests since the last line (how many, how
 * long on average, the slowest), the busiest endpoints by time spent, database connections in use
 * and waited for, and memory. Read with {@code docker logs}; nothing is exposed over HTTP. Counts
 * come from the metrics Spring already keeps, so this costs nothing between lines.
 */
@Component
@ConditionalOnExpression("${kanbancord.performance-log.interval-ms:900000} > 0")
public class PerformanceLog {

    private static final Logger log = LoggerFactory.getLogger(PerformanceLog.class);
    private static final int BUSIEST = 3;

    private final MeterRegistry registry;
    /** Per endpoint ("GET /api/servers/{serverId}/boards"): request count and total ms at the last line. */
    private final Map<String, double[]> last = new HashMap<>();

    public PerformanceLog(MeterRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedRateString = "${kanbancord.performance-log.interval-ms:900000}",
            initialDelayString = "${kanbancord.performance-log.interval-ms:900000}")
    public void write() {
        log.info(summary());
    }

    String summary() {
        Map<String, double[]> now = new HashMap<>();
        double slowest = 0;
        for (Timer timer : registry.find("http.server.requests").timers()) {
            String endpoint = timer.getId().getTag("method") + " " + timer.getId().getTag("uri");
            double[] totals = now.computeIfAbsent(endpoint, key -> new double[2]);
            totals[0] += timer.count();
            totals[1] += timer.totalTime(TimeUnit.MILLISECONDS);
            slowest = Math.max(slowest, timer.max(TimeUnit.MILLISECONDS));
        }
        Map<String, double[]> since = new HashMap<>();
        now.forEach((endpoint, totals) -> {
            double[] before = last.getOrDefault(endpoint, new double[2]);
            double count = totals[0] - before[0];
            if (count > 0) {
                since.put(endpoint, new double[]{count, totals[1] - before[1]});
            }
        });
        last.clear();
        last.putAll(now);

        long requests = Math.round(since.values().stream().mapToDouble(totals -> totals[0]).sum());
        double millis = since.values().stream().mapToDouble(totals -> totals[1]).sum();
        List<String> busiest = since.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> entry) -> entry.getValue()[1]).reversed())
                .limit(BUSIEST)
                .map(entry -> String.format("%s %d× %.0f ms avg", entry.getKey(), Math.round(entry.getValue()[0]),
                        entry.getValue()[1] / entry.getValue()[0]))
                .collect(Collectors.toList());

        return String.format(
                "Performance: %d requests, %.0f ms avg, %.0f ms slowest recently | busiest: %s | db connections %s active, %s idle, %s waiting, of %s; %s timed out | heap %s of %s MB",
                requests, requests > 0 ? millis / requests : 0, slowest,
                busiest.isEmpty() ? "none" : String.join("; ", busiest),
                gauge("hikaricp.connections.active"), gauge("hikaricp.connections.idle"),
                gauge("hikaricp.connections.pending"), gauge("hikaricp.connections.max"),
                counter("hikaricp.connections.timeout"),
                megabytes("jvm.memory.used"), megabytes("jvm.memory.max"));
    }

    private String gauge(String name) {
        Gauge gauge = registry.find(name).gauge();
        return gauge == null ? "?" : String.valueOf(Math.round(gauge.value()));
    }

    private String counter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? "0" : String.valueOf(Math.round(counter.count()));
    }

    /** The heap's total, in MB: summed over its areas (a max of -1 means unbounded and is left out). */
    private String megabytes(String name) {
        double bytes = registry.find(name).tag("area", "heap").gauges().stream()
                .mapToDouble(Gauge::value).filter(value -> value >= 0).sum();
        return String.valueOf(Math.round(bytes / (1024 * 1024)));
    }
}
