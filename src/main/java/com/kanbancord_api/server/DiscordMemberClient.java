package com.kanbancord_api.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kanbancord_api.auth.DiscordApiProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Reads server members from Discord as the bot, for people the API does not store. Only reads: the token is never
 * used to change anything in Discord.
 */
@Component
public class DiscordMemberClient {

    /** A member as Discord has them, in the shape the bot's sync sends. */
    public record DiscordMember(Long userId, String username, String globalName, String avatarUrl, String nickname,
                                List<Long> roleIds, LocalDateTime joinedAt) {
    }

    /** Discord could not be asked (rate limited, down, or the token refused); not an answer about the member. */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final DiscordApiProperties properties;
    private final RestClient client;

    public DiscordMemberClient(DiscordApiProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder().baseUrl(properties.getApiBaseUrl()).build();
    }

    /** Whether a bot token is configured, so members can be looked up. */
    public boolean configured() {
        String token = properties.getBotToken();
        return token != null && !token.isBlank();
    }

    /** The member, or empty when they are not in the server (or the bot is not). */
    public Optional<DiscordMember> fetch(Long serverId, Long userId) {
        try {
            GuildMember member = client.get()
                    .uri("/guilds/{serverId}/members/{userId}", serverId, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + properties.getBotToken())
                    .retrieve()
                    .body(GuildMember.class);
            return Optional.ofNullable(member).map(DiscordMemberClient::toMember);
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            throw new UnavailableException("Could not look up member " + userId + " of server " + serverId, ex);
        }
    }

    /** Members whose username or nickname starts with {@code query}, as Discord's member search finds them. */
    public List<DiscordMember> search(Long serverId, String query, int limit) {
        try {
            List<GuildMember> members = client.get()
                    .uri(builder -> builder.path("/guilds/{serverId}/members/search")
                            .queryParam("query", query)
                            .queryParam("limit", limit)
                            .build(serverId))
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + properties.getBotToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return members == null ? List.of() : members.stream().map(DiscordMemberClient::toMember).toList();
        } catch (RestClientException ex) {
            throw new UnavailableException("Could not search members of server " + serverId, ex);
        }
    }

    private static DiscordMember toMember(GuildMember member) {
        DiscordUser user = member.user();
        LocalDateTime joined = member.joinedAt() == null ? null
                : member.joinedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        List<Long> roles = member.roles() == null ? List.of() : member.roles().stream().map(Long::valueOf).toList();
        return new DiscordMember(Long.valueOf(user.id()), user.username(), user.globalName(), avatarUrl(user),
                member.nick(), roles, joined);
    }

    /** The same address discord.js's {@code displayAvatarURL()} gives, which the bot's sync stores. */
    static String avatarUrl(DiscordUser user) {
        if (user.avatar() != null) {
            return "https://cdn.discordapp.com/avatars/" + user.id() + "/" + user.avatar() + ".webp";
        }
        long index = (Long.parseLong(user.id()) >> 22) % 6;
        return "https://cdn.discordapp.com/embed/avatars/" + index + ".png";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GuildMember(DiscordUser user, String nick, List<String> roles,
                       @JsonProperty("joined_at") OffsetDateTime joinedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DiscordUser(String id, String username, @JsonProperty("global_name") String globalName, String avatar) {
    }
}
