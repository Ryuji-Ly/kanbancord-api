package com.kanbancord_api.session;

import java.time.Instant;
import java.util.UUID;

/** One of the user's active sign-ins. {@code current} marks the one making the request. */
public record SessionResponse(
        UUID sessionId,
        Instant createdAt,
        Instant lastUsedAt,
        String userAgent,
        boolean current) {
}
