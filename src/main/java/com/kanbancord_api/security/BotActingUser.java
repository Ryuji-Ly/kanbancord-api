package com.kanbancord_api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Marks a request the Discord bot made on behalf of a user, from a slash command in {@code guildId}.
 * Kept as the authentication's details, where a website request keeps its sign-in session.
 */
public record BotActingUser(Long guildId) {

    /**
     * Where the current request came from, as recorded in the audit log: {@code DISCORD} for slash
     * commands, {@code ACTIVITY} for the web app running as a Discord Activity, {@code API} for the website.
     */
    public static String currentSource() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "API";
        }
        if (authentication.getDetails() instanceof BotActingUser) {
            return "DISCORD";
        }
        return authentication.getDetails() instanceof AuthenticatedSession session && session.activity() ? "ACTIVITY" : "API";
    }
}
