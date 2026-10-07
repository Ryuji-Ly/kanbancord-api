package com.kanbancord_api.server;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record ServerResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        String name,
        String iconUrl,
        Boolean botPresent,
        @JsonSerialize(using = ToStringSerializer.class) Long ownerId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
