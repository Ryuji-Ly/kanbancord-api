package com.kanbancord_api.config;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Whether the API is up, for the public status page: reachable through the website's /api/ like
 * everything else, where the actuator is not. Says only UP or DOWN (the database included), never
 * any details.
 */
@RestController
public class PublicHealthController {

    private final HealthEndpoint healthEndpoint;

    public PublicHealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean up = Status.UP.equals(healthEndpoint.health().getStatus());
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
