package com.kanbancord_api.realtime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record RealtimeSessionResponse(
        String type,
        String sessionId,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        Instant connectedAt,
        Instant lastSeenAt,
        Instant serverTime,
        List<String> allowedSendDestinations,
        List<RealtimeSubscriptionResponse> subscriptions) {

    public RealtimeSessionResponse {
        if (allowedSendDestinations == null) {
            allowedSendDestinations = new ArrayList<>();
        }
        if (subscriptions == null) {
            subscriptions = new ArrayList<>();
        }
    }
}
