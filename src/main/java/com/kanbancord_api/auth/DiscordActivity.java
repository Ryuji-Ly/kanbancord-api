package com.kanbancord_api.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * KanbanCord as a Discord Activity. Discord serves it through its own proxy, from
 * {@code https://<client id>.discordsays.com}, so requests from the Activity carry that origin.
 */
@Component
public class DiscordActivity {

    private final DiscordOAuthProperties discordOAuthProperties;

    public DiscordActivity(DiscordOAuthProperties discordOAuthProperties) {
        this.discordOAuthProperties = discordOAuthProperties;
    }

    /** The Activity's origin, or null when no Discord client id is configured. */
    public String origin() {
        String clientId = discordOAuthProperties.getClientId();
        return clientId == null || clientId.isBlank() ? null : "https://" + clientId.trim() + ".discordsays.com";
    }

    /** {@code origins} with the Activity's origin added, so it does not have to be configured separately. */
    public List<String> withOrigin(List<String> origins) {
        List<String> all = new ArrayList<>(origins);
        String origin = origin();
        if (origin != null && !all.contains(origin)) {
            all.add(origin);
        }
        return List.copyOf(all);
    }
}
