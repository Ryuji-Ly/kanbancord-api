package com.kanbancord_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "kanbancord.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "https://kanbancord.com",
            "https://www.kanbancord.com");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
