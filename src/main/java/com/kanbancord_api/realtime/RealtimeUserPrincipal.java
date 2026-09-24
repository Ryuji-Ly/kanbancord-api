package com.kanbancord_api.realtime;

import java.security.Principal;
import java.util.UUID;

public class RealtimeUserPrincipal implements Principal {

    private final Long userId;
    private final UUID authSessionId;

    /** {@code authSessionId} is the sign-in session the connection was opened with; revoking it closes the connection. */
    public RealtimeUserPrincipal(Long userId, UUID authSessionId) {
        this.userId = userId;
        this.authSessionId = authSessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getAuthSessionId() {
        return authSessionId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}