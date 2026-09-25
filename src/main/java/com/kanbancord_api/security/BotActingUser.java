package com.kanbancord_api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Marks a request the Discord bot made on behalf of a user, from a slash command in {@code guildId}.
 * Kept as the authentication's details, where a website request keeps its sign-in session.
 */
public record BotActingUser(Long guildId) {

    /** Where the current request came from, as recorded in the audit log. */
    public static String currentSource() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getDetails() instanceof BotActingUser ? "DISCORD" : "API";
    }
}
