package com.kanbancord_api.auth;

import jakarta.validation.constraints.NotBlank;

public class DiscordOAuthCodeExchangeRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String redirectUri;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }
}
