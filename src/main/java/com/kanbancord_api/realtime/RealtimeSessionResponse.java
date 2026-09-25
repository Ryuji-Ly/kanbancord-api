package com.kanbancord_api.realtime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RealtimeSessionResponse {

    private String type;
    private String sessionId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private Instant connectedAt;
    private Instant lastSeenAt;
    private Instant serverTime;
    private List<String> allowedSendDestinations = new ArrayList<>();
    private List<RealtimeSubscriptionResponse> subscriptions = new ArrayList<>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(Instant connectedAt) {
        this.connectedAt = connectedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getServerTime() {
        return serverTime;
    }

    public void setServerTime(Instant serverTime) {
        this.serverTime = serverTime;
    }

    public List<String> getAllowedSendDestinations() {
        return allowedSendDestinations;
    }

    public void setAllowedSendDestinations(List<String> allowedSendDestinations) {
        this.allowedSendDestinations = allowedSendDestinations;
    }

    public List<RealtimeSubscriptionResponse> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(List<RealtimeSubscriptionResponse> subscriptions) {
        this.subscriptions = subscriptions;
    }
}