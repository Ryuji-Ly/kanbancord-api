package com.kanbancord_api.realtime;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.ServerAccessValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RealtimeDestinationAuthorizationService {

    private static final Pattern SERVER_TOPIC_PATTERN = Pattern.compile("^/topic/servers/(\\d+)$");
    private static final Pattern BOARD_TOPIC_PATTERN = Pattern.compile("^/topic/servers/(\\d+)/boards/(\\d+)$");
    private static final Set<String> USER_QUEUE_DESTINATIONS = Set.of("/user/queue/session");
    private static final Set<String> ALLOWED_SEND_DESTINATIONS = Set.of("/app/session/ping");

    private final ServerAccessValidator serverAccessValidator;
    private final ResourceValidator resourceValidator;

    public RealtimeDestinationAuthorizationService(
            ServerAccessValidator serverAccessValidator,
            ResourceValidator resourceValidator) {
        this.serverAccessValidator = serverAccessValidator;
        this.resourceValidator = resourceValidator;
    }

    public AuthorizedSubscription authorizeSubscribe(Long userId, String destination) {
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("A realtime destination is required");
        }

        if (USER_QUEUE_DESTINATIONS.contains(destination)) {
            return new AuthorizedSubscription(null, destination, "USER", null, null, Instant.now());
        }

        Matcher boardMatcher = BOARD_TOPIC_PATTERN.matcher(destination);
        if (boardMatcher.matches()) {
            Long serverId = Long.parseLong(boardMatcher.group(1));
            Long boardId = Long.parseLong(boardMatcher.group(2));
            serverAccessValidator.validateUserInServer(userId, serverId);
            resourceValidator.requireBoardInServer(boardId, serverId);
            return new AuthorizedSubscription(null, destination, "BOARD", serverId, boardId, Instant.now());
        }

        Matcher serverMatcher = SERVER_TOPIC_PATTERN.matcher(destination);
        if (serverMatcher.matches()) {
            Long serverId = Long.parseLong(serverMatcher.group(1));
            serverAccessValidator.validateUserInServer(userId, serverId);
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