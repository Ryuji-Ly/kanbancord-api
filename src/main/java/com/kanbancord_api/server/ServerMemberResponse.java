package com.kanbancord_api.server;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record ServerMemberResponse(
        Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String nickname,
        String displayName,
        String username,
        String avatarUrl,
        LocalDateTime joinedAt) {
}
