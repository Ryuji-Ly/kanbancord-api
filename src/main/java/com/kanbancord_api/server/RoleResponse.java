package com.kanbancord_api.server;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record RoleResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long roleId,
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        String name,
        Integer color,
        Integer position,
        Long discordPermissions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
