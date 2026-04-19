package com.kanbancord_api.dto;

import jakarta.validation.constraints.NotBlank;

public class DiscordLoginRequest {

    @NotBlank(message = "Discord access token is required")
    private String accessToken;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }
}
