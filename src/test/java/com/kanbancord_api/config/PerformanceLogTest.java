package com.kanbancord_api.config;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceLogTest {

    private static Timer endpoint(SimpleMeterRegistry registry, String method, String uri) {
        return Timer.builder("http.server.requests").tag("method", method).tag("uri", uri).register(registry);
    }

    @Test
    void eachLineCountsOnlyTheRequestsSinceTheLastOne_busiestFirst() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PerformanceLog performance = new PerformanceLog(registry);
        Timer snapshot = endpoint(registry, "GET", "/api/servers/{serverId}/boards/{boardId}/snapshot");
        Timer move = endpoint(registry, "PATCH", "/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/move");
        snapshot.record(Duration.ofMillis(40));
        snapshot.record(Duration.ofMillis(60));
        move.record(Duration.ofMillis(300));

        String first = performance.summary();
        assertTrue(first.contains("3 requests, 133 ms avg"), first);
        assertTrue(first.indexOf("move 1× 300 ms avg") < first.indexOf("snapshot 2× 50 ms avg"), first);

        snapshot.record(Duration.ofMillis(10));
        String second = performance.summary();
        assertTrue(second.contains("1 requests, 10 ms avg"), second);
        assertTrue(!second.contains("move"), "an endpoint with no new requests is not listed: " + second);
        assertTrue(second.contains("db connections ? active"), "without a pool the numbers are unknown: " + second);
    }
}
