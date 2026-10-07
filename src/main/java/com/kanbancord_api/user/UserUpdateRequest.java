package com.kanbancord_api.user;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record UserUpdateRequest(
        @Size(max = 100) String globalName,
        @Size(max = 255) String avatarUrl,
        Map<String, Object> preferences) {
}
