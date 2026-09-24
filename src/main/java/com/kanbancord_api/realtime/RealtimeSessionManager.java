package com.kanbancord_api.realtime;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeSessionManager {

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionSnapshot registerConnection(String sessionId, Long userId, String principalName,
            List<String> allowedSendDestinations) {
        Instant now = Instant.now();
        SessionState sessionState = new SessionState(sessionId, userId, principalName, now, now,
                List.copyOf(allowedSendDestinations), new ConcurrentHashMap<>());
        sessions.put(sessionId, sessionState);
        return snapshot(sessionState);
    }

    public Optional<Long> userId(String sessionId) {
        SessionState sessionState = sessionId == null ? null : sessions.get(sessionId);
        return sessionState == null ? Optional.empty() : Optional.ofNullable(sessionState.userId);
    }

    public void touch(String sessionId) {
        SessionState sessionState = sessions.get(sessionId);
        if (sessionState != null) {
            sessionState.lastSeenAt = Instant.now();
        }
    }

    public void registerSubscription(
            String sessionId,
            RealtimeDestinationAuthorizationService.AuthorizedSubscription subscription) {
        SessionState sessionState = sessions.get(sessionId);
        if (sessionState == null) {
            return;
        }
        sessionState.lastSeenAt = Instant.now();
        sessionState.subscriptions.put(
                subscription.subscriptionId(),
                new SubscriptionState(
                        subscription.subscriptionId(),
                        subscription.destination(),
                        subscription.scopeType(),
                        subscription.serverId(),
                        subscription.boardId(),
                        subscription.subscribedAt()));
    }

    public void unregisterSubscription(String sessionId, String subscriptionId) {
        SessionState sessionState = sessions.get(sessionId);
        if (sessionState == null || subscriptionId == null) {
            return;
        }
        sessionState.lastSeenAt = Instant.now();
        sessionState.subscriptions.remove(subscriptionId);
    }

    public void unregisterSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** Every live subscription to the server's topic or any of its board topics. */
    public List<ActiveSubscription> subscriptionsForServer(Long serverId) {
        List<ActiveSubscription> result = new ArrayList<>();
        sessions.values().forEach(session -> session.subscriptions.values().forEach(subscription -> {
            if (serverId.equals(subscription.serverId())) {
                result.add(new ActiveSubscription(
                        session.sessionId,
                        session.userId,
                        session.principalName,
                        subscription.subscriptionId(),
                        subscription.destination(),
                        subscription.serverId(),
                        subscription.boardId()));
            }
        }));
        return result;
    }

    public Optional<SessionSnapshot> snapshot(String sessionId) {
        SessionState sessionState = sessions.get(sessionId);
        return sessionState == null ? Optional.empty() : Optional.of(snapshot(sessionState));
    }

    private SessionSnapshot snapshot(SessionState sessionState) {
        List<SubscriptionSnapshot> subscriptions = sessionState.subscriptions.values().stream()
                .sorted(Comparator.comparing(SubscriptionState::subscribedAt))
                .map(item -> new SubscriptionSnapshot(
                        item.subscriptionId(),
                        item.destination(),
                        item.scopeType(),
                        item.serverId(),
                        item.boardId(),
                        item.subscribedAt()))
                .toList();

        return new SessionSnapshot(
                sessionState.sessionId,
                sessionState.userId,
                sessionState.principalName,
                sessionState.connectedAt,
                sessionState.lastSeenAt,
                new ArrayList<>(sessionState.allowedSendDestinations),
                subscriptions);
    }

    private static final class SessionState {
        private final String sessionId;
        private final Long userId;
        private final String principalName;
        private final Instant connectedAt;
        private volatile Instant lastSeenAt;
        private final List<String> allowedSendDestinations;
        private final Map<String, SubscriptionState> subscriptions;

        private SessionState(
                String sessionId,
                Long userId,
                String principalName,
                Instant connectedAt,
                Instant lastSeenAt,
                List<String> allowedSendDestinations,
                Map<String, SubscriptionState> subscriptions) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.principalName = principalName;
            this.connectedAt = connectedAt;
            this.lastSeenAt = lastSeenAt;
            this.allowedSendDestinations = allowedSendDestinations;
            this.subscriptions = subscriptions;
        }
    }

    private record SubscriptionState(
            String subscriptionId,
            String destination,
            String scopeType,
            Long serverId,
            Long boardId,
            Instant subscribedAt) {
    }

    public record ActiveSubscription(
            String sessionId,
            Long userId,
            String principalName,
            String subscriptionId,
            String destination,
            Long serverId,
            Long boardId) {
    }

    public record SessionSnapshot(
            String sessionId,
            Long userId,
            String principalName,
            Instant connectedAt,
            Instant lastSeenAt,
            List<String> allowedSendDestinations,
            List<SubscriptionSnapshot> subscriptions) {
    }

    public record SubscriptionSnapshot(
            String subscriptionId,
            String destination,
            String scopeType,
            Long serverId,
            Long boardId,
            Instant subscribedAt) {
    }
}