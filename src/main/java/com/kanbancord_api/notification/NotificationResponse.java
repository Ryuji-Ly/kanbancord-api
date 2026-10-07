package com.kanbancord_api.notification;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record NotificationResponse(
        Long notificationId,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String type,
        String entityType,
        Long entityId,
        String message,
        Boolean isRead,
        Map<String, Object> metadata,
        LocalDateTime createdAt) {
}
