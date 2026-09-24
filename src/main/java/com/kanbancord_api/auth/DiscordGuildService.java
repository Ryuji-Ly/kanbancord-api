package com.kanbancord_api.auth;

import com.kanbancord_api.exception.DiscordReauthorizationRequiredException;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Discord servers a user is in, fetched with their stored Discord token. Lists are cached per
 * user for a few minutes: they rarely change and Discord rate-limits this endpoint tightly.
 */
@Service
public class DiscordGuildService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int SWEEP_THRESHOLD = 1_000;

    private final DiscordApiProperties discordApiProperties;
    private final DiscordCredentialService discordCredentialService;
    private final Map<Long, CachedGuilds> cache = new ConcurrentHashMap<>();

    public DiscordGuildService(
            DiscordApiProperties discordApiProperties,
            DiscordCredentialService discordCredentialService) {
        this.discordApiProperties = discordApiProperties;
        this.discordCredentialService = discordCredentialService;
    }

    public List<DiscordGuildResponse> guilds(Long userId) {
        Instant now = Instant.now();
        CachedGuilds cached = cache.get(userId);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.guilds();
        }

        try {
            List<DiscordGuildResponse> guilds = fetch(discordCredentialService.accessToken(userId));
            if (cache.size() > SWEEP_THRESHOLD) {
                cache.values().removeIf(entry -> entry.fetchedAt().plus(CACHE_TTL).isBefore(now));
            }
            cache.put(userId, new CachedGuilds(guilds, now));
            return guilds;
        } catch (HttpClientErrorException.Unauthorized ex) {
            discordCredentialService.rejected(userId);
            throw new DiscordReauthorizationRequiredException();
        } catch (HttpClientErrorException.TooManyRequests ex) {
            // A slightly old list beats an error.
            if (cached != null) {
                return cached.guilds();
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Discord is busy, try again shortly");
        } catch (RestClientException ex) {
            if (cached != null) {
                return cached.guilds();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load your Discord servers");
        }
    }

    @EventListener
    public void onCredentialsForgotten(DiscordCredentialService.DiscordCredentialsForgotten event) {
        cache.remove(event.userId());
    }

    private List<DiscordGuildResponse> fetch(String accessToken) {
        List<DiscordGuildResponse> guilds = RestClient.builder()
                .baseUrl(discordApiProperties.getApiBaseUrl())
                .build()
                .get()
                .uri("/users/@me/guilds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return guilds == null ? List.of() : List.copyOf(guilds);
    }

    private record CachedGuilds(List<DiscordGuildResponse> guilds, Instant fetchedAt) {
    }
}
