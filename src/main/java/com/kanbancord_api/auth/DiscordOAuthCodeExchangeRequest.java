package com.kanbancord_api.auth;

import jakarta.validation.constraints.NotBlank;

public record DiscordOAuthCodeExchangeRequest(
        @NotBlank String code,
        @NotBlank String redirectUri) {
}
