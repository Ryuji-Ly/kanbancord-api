package com.kanbancord_api.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kanbancord_api.exception.AccessDeniedException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class DiscordOAuthService {

    private final DiscordApiProperties discordApiProperties;
    private final DiscordOAuthProperties discordOAuthProperties;

    public DiscordOAuthService(
            DiscordApiProperties discordApiProperties,
            DiscordOAuthProperties discordOAuthProperties) {
        this.discordApiProperties = discordApiProperties;
        this.discordOAuthProperties = discordOAuthProperties;
    }

    public String exchangeCodeForAccessToken(String code, String redirectUri) {
        String clientId = discordOAuthProperties.getClientId();
        String clientSecret = discordOAuthProperties.getClientSecret();

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new AccessDeniedException(
                    "Discord OAuth is not configured. Set KANBANCORD_DISCORD_CLIENT_ID and KANBANCORD_DISCORD_CLIENT_SECRET.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        try {
            RestClient client = RestClient.builder()
                    .baseUrl(discordApiProperties.getApiBaseUrl())
                    .build();

            DiscordTokenResponse tokenResponse = client.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(DiscordTokenResponse.class);

            if (tokenResponse == null || tokenResponse.accessToken() == null || tokenResponse.accessToken().isBlank()) {
                throw new AccessDeniedException("Unable to exchange Discord authorization code");
            }

            return tokenResponse.accessToken();
        } catch (RestClientException ex) {
            throw new AccessDeniedException("Invalid or expired Discord authorization code");
        }
    }

    private record DiscordTokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
