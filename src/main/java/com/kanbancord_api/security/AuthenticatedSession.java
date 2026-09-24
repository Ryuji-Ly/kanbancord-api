package com.kanbancord_api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/** The sign-in session behind the current request, kept as the authentication's details. */
public record AuthenticatedSession(UUID sessionId) {

    public static Optional<UUID> currentSessionId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof AuthenticatedSession session) {
            return Optional.of(session.sessionId());
        }
        return Optional.empty();
    }
}
