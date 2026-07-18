package com.kanbancord_api.realtime;

import java.security.Principal;

public class RealtimeUserPrincipal implements Principal {

    private final Long userId;

    public RealtimeUserPrincipal(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}