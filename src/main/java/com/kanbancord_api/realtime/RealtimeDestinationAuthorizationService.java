package com.kanbancord_api.realtime;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.service.ServerAccessValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RealtimeDestinationAuthorizationService {

    private static final Set<String> USER_QUEUE_DESTINATIONS = Set.of("/user/queue/session");
    private static final Set<String> ALLOWED_SEND_DESTINATIONS = Set.of("/app/session/ping");

    private final ServerAccessValidator serverAccessValidator;

    public RealtimeDestinationAuthorizationService(ServerAccessValidator serverAccessValidator) {
        this.serverAccessValidator = serverAccessValidator;
    }

    public AuthorizedSubscription authorizeSubscribe(Long userId, String destination) {
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("A realtime destination is required");
        }

        if (USER_QUEUE_DESTINATIONS.contains(destination)) {
            return new AuthorizedSubscription(null, destination, "USER", null, null, Instant.now());
        }

        Optional<RealtimeTopics.Topic> topic = RealtimeTopics.parse(destination);
        if (topic.isPresent() && topic.get().isBoardTopic()) {
            Long serverId = topic.get().serverId();
            Long boardId = topic.get().boardId();
            // Board events carry task contents; only users who can view the board may subscribe.
            serverAccessValidator.validateUserHasPermission(userId, serverId, boardId, "VIEW_BOARD");
            return new AuthorizedSubscription(null, destination, "BOARD", serverId, boardId, Instant.now());
        }

        if (topic.isPresent()) {
            Long serverId = topic.get().serverId();
            serverAccessValidator.validateUserHasPermission(userId, serverId, null, "VIEW_SERVER");
            return new AuthorizedSubscription(null, destination, "SERVER", serverId, null, Instant.now());
        }

        throw new AccessDeniedException("Realtime subscription is not allowed for destination: " + destination);
    }

    public void authorizeSend(Long userId, String destination) {
        if (destination == null || !ALLOWED_SEND_DESTINATIONS.contains(destination)) {
            throw new AccessDeniedException("Realtime send is not allowed for destination: " + destination);
        }
    }

    public List<String> getAllowedSendDestinations() {
        return List.copyOf(ALLOWED_SEND_DESTINATIONS);
    }

    public record AuthorizedSubscription(
            String subscriptionId,
            String destination,
            String scopeType,
            Long serverId,
            Long boardId,
            Instant subscribedAt) {

        public AuthorizedSubscription withSubscriptionId(String value) {
            return new AuthorizedSubscription(value, destination, scopeType, serverId, boardId, subscribedAt);
        }
    }
}