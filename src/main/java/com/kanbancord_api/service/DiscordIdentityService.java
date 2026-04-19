package com.kanbancord_api.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kanbancord_api.config.DiscordApiProperties;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.model.User;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class DiscordIdentityService {

    private final DiscordApiProperties discordApiProperties;
    private final UserService userService;

    public DiscordIdentityService(DiscordApiProperties discordApiProperties, UserService userService) {
        this.discordApiProperties = discordApiProperties;
        this.userService = userService;
    }

    public User authenticateAndSyncUser(String discordAccessToken) {
        DiscordUserProfile profile = fetchDiscordProfile(discordAccessToken);

        Long userId;
        try {
            userId = Long.parseLong(profile.id());
        } catch (NumberFormatException ex) {
            throw new AccessDeniedException("Discord returned an invalid user id");
        }

        User user = userService.findById(userId).orElseGet(User::new);
        user.setUserId(userId);
        user.setUsername(profile.username());
        user.setGlobalName(profile.globalName());
        user.setAvatarUrl(resolveAvatarUrl(profile.id(), profile.avatar()));

        return userService.update(user);
    }

    private DiscordUserProfile fetchDiscordProfile(String discordAccessToken) {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(discordApiProperties.getApiBaseUrl())
                    .build();

            DiscordUserProfile profile = client.get()
                    .uri("/users/@me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + discordAccessToken)
                    .retrieve()
                    .body(DiscordUserProfile.class);

            if (profile == null || profile.id() == null || profile.username() == null) {
                throw new AccessDeniedException("Unable to read Discord user profile");
            }

            return profile;
        } catch (RestClientException ex) {
            throw new AccessDeniedException("Invalid or expired Discord access token");
        }
    }

    private String resolveAvatarUrl(String userId, String avatarHash) {
        if (avatarHash == null || avatarHash.isBlank()) {
            return null;
        }
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + ".png";
    }

    private record DiscordUserProfile(
            String id,
            String username,
            @JsonProperty("global_name") String globalName,
            String avatar) {
    }
}
