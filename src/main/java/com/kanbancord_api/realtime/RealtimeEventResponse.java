package com.kanbancord_api.realtime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;

public record RealtimeEventResponse(
        String eventId,
        String eventType,
        String scopeType,
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        Long boardId,
        String entityType,
        Long entityId,
        @JsonSerialize(using = ToStringSerializer.class) Long actorUserId,
        Instant occurredAt,
        Object payload) {
}
