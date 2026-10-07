package com.kanbancord_api.auth;

import com.kanbancord_api.user.UserResponse;

public record AuthResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        UserResponse user,
        String sessionId) {
}
