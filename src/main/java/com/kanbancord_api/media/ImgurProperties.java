package com.kanbancord_api.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Imgur, where uploaded images and videos are stored. Uploads are anonymous: only the application's
 * Client ID is needed, and without it uploads are refused as not set up.
 */
@Component
@ConfigurationProperties(prefix = "kanbancord.imgur")
public class ImgurProperties {

    private String clientId = "";
    private String apiBaseUrl = "https://api.imgur.com";
    /** Imgur's own limit for images is 20 MB. */
    private long maxImageBytes = 20L * 1024 * 1024;
    /** Imgur takes more, but uploads pass through Cloudflare, which refuses bodies over 100 MB. */
    private long maxVideoBytes = 50L * 1024 * 1024;

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public long getMaxVideoBytes() {
        return maxVideoBytes;
    }

    public void setMaxVideoBytes(long maxVideoBytes) {
        this.maxVideoBytes = maxVideoBytes;
    }
}
