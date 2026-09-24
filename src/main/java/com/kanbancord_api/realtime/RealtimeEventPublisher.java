package com.kanbancord_api.realtime;

import com.kanbancord_api.dto.RealtimeEventResponse;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Announces committed changes to realtime subscribers: on the board's topic, and also on the server
 * topic for server-wide changes. Who may receive each message is decided per recipient by
 * {@link RealtimeOutboundInterceptor}.
 */
@Service
public class RealtimeEventPublisher {

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final RealtimeAccessCache realtimeAccessCache;

    public RealtimeEventPublisher(
            SimpMessagingTemplate simpMessagingTemplate,
            RealtimeAccessCache realtimeAccessCache) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.realtimeAccessCache = realtimeAccessCache;
    }

    /** Runs after the change commits, so subscribers never hear about a change that was rolled back. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onDomainEvent(DomainEvent event) {
        if (event.type().entityType() == EventType.EntityType.PERMISSION) {
            // Rule changes can grant or revoke access; recipients must be judged on the new rules.
            realtimeAccessCache.invalidateAll();
        }

        List<String> destinations = new ArrayList<>();
        if (event.boardId() != null) {
            destinations.add(RealtimeTopics.boardTopic(event.serverId(), event.boardId()));
        }
        if (event.boardId() == null || event.type().serverWide()) {
            destinations.add(RealtimeTopics.serverTopic(event.serverId()));
        }

        // Lets RealtimeOutboundInterceptor withhold board events on the server topic from users who cannot view the board.
        Map<String, Object> headers = event.boardId() == null
                ? Map.of()
                : Map.of(RealtimeTopics.BOARD_ID_HEADER, event.boardId().toString());

        RealtimeEventResponse message = toMessage(event);
        destinations.forEach(destination -> simpMessagingTemplate.convertAndSend(destination, message, headers));
    }

    private static RealtimeEventResponse toMessage(DomainEvent event) {
        RealtimeEventResponse message = new RealtimeEventResponse();
        message.setEventId(UUID.randomUUID().toString());
        message.setEventType(event.type().name());
        message.setScopeType(event.boardId() != null ? "BOARD" : "SERVER");
        message.setServerId(event.serverId());
        message.setBoardId(event.boardId());
        message.setEntityType(event.type().entityType().name());
        message.setEntityId(event.entityId());
        message.setActorUserId(event.actorUserId());
        message.setOccurredAt(Instant.now());
        message.setPayload(event.snapshot());
        return message;
    }
}
