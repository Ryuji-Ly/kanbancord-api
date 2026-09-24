package com.kanbancord_api.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kanbancord_api.exception.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

@Service
public class DiscordOAuthService {

    private static final Logger log = LoggerFactory.getLogger(DiscordOAuthService.class);

    private final DiscordApiProperties discordApiProperties;
    private final DiscordOAuthProperties discordOAuthProperties;

    public DiscordOAuthService(
            DiscordApiProperties discordApiProperties,
            DiscordOAuthProperties discordOAuthProperties) {
        this.discordApiProperties = discordApiProperties;
        this.discordOAuthProperties = discordOAuthProperties;
    }

    /** Tokens Discord issued for a user. {@code refreshToken} may be null. */
    public record DiscordTokens(String accessToken, String refreshToken, Instant expiresAt, String scope) {
    }

    /** Thrown when Discord no longer accepts a refresh token; the user has to authorize again. */
    public static class DiscordAuthorizationRevokedException extends RuntimeException {
        public DiscordAuthorizationRevokedException() {
            super("Discord authorization was revoked");
        }
    }

    public DiscordTokens exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        try {
            return requestTokens(form);
        } catch (RestClientException ex) {
            throw new AccessDeniedException("Invalid or expired Discord authorization code");
        }
    }

    public DiscordTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);

        try {
            return requestTokens(form);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.Unauthorized ex) {
            throw new DiscordAuthorizationRevokedException();
        }
    }

    /** Asks Discord to invalidate a token. Best effort: a failure is logged and otherwise ignored. */
    public void revoke(String token) {
        MultiValueMap<String, String> form = clientForm();
        form.add("token", token);
        try {
            client().post()
                    .uri("/oauth2/token/revoke")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Failed to revoke a Discord token: {}", ex.getMessage());
        }
    }

    private DiscordTokens requestTokens(MultiValueMap<String, String> form) {
        DiscordTokenResponse response = client().post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(DiscordTokenResponse.class);

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new AccessDeniedException("Discord did not return an access token");
        }
        long expiresIn = response.expiresIn() == null ? 3600 : response.expiresIn();
        return new DiscordTokens(
                response.accessToken(),
                response.refreshToken(),
                Instant.now().plusSeconds(expiresIn),
                response.scope());
    }

    private MultiValueMap<String, String> clientForm() {
        String clientId = discordOAuthProperties.getClientId();
        String clientSecret = discordOAuthProperties.getClientSecret();
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new AccessDeniedException(
                    "Discord OAuth is not configured. Set KANBANCORD_DISCORD_CLIENT_ID and KANBANCORD_DISCORD_CLIENT_SECRET.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return form;
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(discordApiProperties.getApiBaseUrl())
                .build();
    }

    private record DiscordTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Long expiresIn,
            String scope) {
    }
}
