package com.kanbancord_api.realtime;

import com.kanbancord_api.event.UserEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Sends each committed {@link UserEvent} to its user's own queue. Only that user can subscribe to it:
 * user destinations resolve to the connections of the authenticated user.
 */
@Service
public class UserEventPublisher {

    private final SimpMessagingTemplate simpMessagingTemplate;

    public UserEventPublisher(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onUserEvent(UserEvent event) {
        simpMessagingTemplate.convertAndSendToUser(
                String.valueOf(event.userId()),
                RealtimeTopics.USER_QUEUE,
                new Message(event.type().name(), Instant.now(), event.payload()));
    }

    /** What the client receives on {@code /user/queue/me}. */
    public record Message(String eventType, Instant occurredAt, Object payload) {
    }
}
