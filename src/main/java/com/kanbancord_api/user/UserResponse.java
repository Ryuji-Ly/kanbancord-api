package com.kanbancord_api.user;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record UserResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String username,
        String globalName,
        String avatarUrl,
        Map<String, Object> preferences,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
