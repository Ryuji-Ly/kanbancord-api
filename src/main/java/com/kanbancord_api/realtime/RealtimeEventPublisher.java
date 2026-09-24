package com.kanbancord_api.realtime;

import com.kanbancord_api.dto.RealtimeEventResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public RealtimeEventResponse newEvent(
            String eventType,
            String scopeType,
            Long serverId,
            Long boardId,
            String entityType,
            Long entityId,
            Long actorUserId,
            Object payload) {
        RealtimeEventResponse event = new RealtimeEventResponse();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setScopeType(scopeType);
        event.setServerId(serverId);
        event.setBoardId(boardId);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setActorUserId(actorUserId);
        event.setOccurredAt(Instant.now());
        event.setPayload(payload);
        return event;
    }

    public void publishToServerTopic(Long serverId, RealtimeEventResponse event) {
        publish(List.of(RealtimeTopics.serverTopic(serverId)), event);
    }

    public void publishToBoardTopic(Long serverId, Long boardId, RealtimeEventResponse event) {
        publish(List.of(RealtimeTopics.boardTopic(serverId, boardId)), event);
    }

    public void publishToServerAndBoardTopics(Long serverId, Long boardId, RealtimeEventResponse event) {
        publish(List.of(RealtimeTopics.serverTopic(serverId), RealtimeTopics.boardTopic(serverId, boardId)), event);
    }

    private void publish(List<String> destinations, RealtimeEventResponse event) {
        List<String> uniqueDestinations = new ArrayList<>(new LinkedHashSet<>(destinations));
        // Lets RealtimeOutboundInterceptor withhold board events on the server topic from users who cannot view the board.
        Map<String, Object> headers = event.getBoardId() == null
                ? Map.of()
                : Map.of(RealtimeTopics.BOARD_ID_HEADER, event.getBoardId().toString());

        Runnable dispatch = () -> {
            if ("PERMISSION".equals(event.getEntityType())) {
                // Rule changes can grant or revoke access; recipients must be judged on the new rules.
                realtimeAccessCache.invalidateAll();
            }
            uniqueDestinations.forEach(destination -> simpMessagingTemplate.convertAndSend(destination, event, headers));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
            return;
        }

        dispatch.run();
    }
}