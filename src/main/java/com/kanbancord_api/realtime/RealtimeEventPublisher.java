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
import java.util.UUID;

@Service
public class RealtimeEventPublisher {

    private final SimpMessagingTemplate simpMessagingTemplate;

    public RealtimeEventPublisher(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
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
        publish(List.of(serverTopic(serverId)), event);
    }

    public void publishToBoardTopic(Long serverId, Long boardId, RealtimeEventResponse event) {
        publish(List.of(boardTopic(serverId, boardId)), event);
    }

    public void publishToServerAndBoardTopics(Long serverId, Long boardId, RealtimeEventResponse event) {
        publish(List.of(serverTopic(serverId), boardTopic(serverId, boardId)), event);
    }

    private void publish(List<String> destinations, RealtimeEventResponse event) {
        List<String> uniqueDestinations = new ArrayList<>(new LinkedHashSet<>(destinations));

        Runnable dispatch = () -> uniqueDestinations
                .forEach(destination -> simpMessagingTemplate.convertAndSend(destination, event));
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

    private String serverTopic(Long serverId) {
        return "/topic/servers/" + serverId;
    }

    private String boardTopic(Long serverId, Long boardId) {
        return "/topic/servers/" + serverId + "/boards/" + boardId;
    }
}