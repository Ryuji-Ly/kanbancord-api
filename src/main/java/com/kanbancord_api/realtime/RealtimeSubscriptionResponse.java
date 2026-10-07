package com.kanbancord_api.realtime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;

public record RealtimeSubscriptionResponse(
        String subscriptionId,
        String destination,
        String scopeType,
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        Long boardId,
        Instant subscribedAt) {
}
