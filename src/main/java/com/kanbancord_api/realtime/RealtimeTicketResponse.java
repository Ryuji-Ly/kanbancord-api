package com.kanbancord_api.realtime;

import java.time.Instant;

public record RealtimeTicketResponse(
        String ticket,
        Instant expiresAt,
        String websocketPath) {
}
