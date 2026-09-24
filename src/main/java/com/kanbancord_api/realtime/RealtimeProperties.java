package com.kanbancord_api.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "kanbancord.realtime")
public class RealtimeProperties {

    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173", "https://kanbancord.com", "https://www.kanbancord.com");
    private long ticketTtlSeconds = 30;
    /** How long a "may this user receive this server's/board's events" decision is reused. */
    private long accessCacheTtlSeconds = 10;

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public long getTicketTtlSeconds() {
        return ticketTtlSeconds;
    }

    public void setTicketTtlSeconds(long ticketTtlSeconds) {
        this.ticketTtlSeconds = ticketTtlSeconds;
    }

    public long getAccessCacheTtlSeconds() {
        return accessCacheTtlSeconds;
    }

    public void setAccessCacheTtlSeconds(long accessCacheTtlSeconds) {
        this.accessCacheTtlSeconds = accessCacheTtlSeconds;
    }
}