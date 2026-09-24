package com.kanbancord_api.realtime;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * Ends live subscriptions whose user has lost access, instead of leaving them open and silent.
 *
 * <p>{@link RealtimeOutboundInterceptor} already withholds events from users who cannot view them.
 * This also removes the subscription from the broker and tells the client, so the page can stop
 * showing what the user may no longer see.
 */
@Service
public class RealtimeSubscriptionRevoker {

    public static final String SESSION_QUEUE = "/queue/session";

    private final RealtimeSessionManager realtimeSessionManager;
    private final RealtimeAccessCache realtimeAccessCache;
    private final SimpMessagingTemplate simpMessagingTemplate;

    public RealtimeSubscriptionRevoker(
            RealtimeSessionManager realtimeSessionManager,
            RealtimeAccessCache realtimeAccessCache,
            SimpMessagingTemplate simpMessagingTemplate) {
        this.realtimeSessionManager = realtimeSessionManager;
        this.realtimeAccessCache = realtimeAccessCache;
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    /**
     * Call after anything that can change who may view the server or its boards has been saved:
     * permission rules, roles, memberships. Forgets cached access decisions and ends each
     * subscription to the server whose user can no longer view it.
     */
    public void accessChanged(Long serverId) {
        realtimeAccessCache.invalidateAll();
        for (RealtimeSessionManager.ActiveSubscription subscription : realtimeSessionManager.subscriptionsForServer(serverId)) {
            if (!realtimeAccessCache.canView(subscription.userId(), subscription.serverId(), subscription.boardId())) {
                revoke(subscription, "ACCESS_LOST");
            }
        }
    }

    /** Ends every subscription to a deleted board's topic, once its deletion has been announced. */
    public void boardDeleted(Long serverId, Long boardId) {
        for (RealtimeSessionManager.ActiveSubscription subscription : realtimeSessionManager.subscriptionsForServer(serverId)) {
            if (boardId.equals(subscription.boardId())) {
                revoke(subscription, "BOARD_DELETED");
            }
        }
    }

    private void revoke(RealtimeSessionManager.ActiveSubscription subscription, String reason) {
        SimpMessageHeaderAccessor unsubscribe = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        unsubscribe.setSessionId(subscription.sessionId());
        unsubscribe.setSubscriptionId(subscription.subscriptionId());
        simpMessagingTemplate.getMessageChannel()
                .send(MessageBuilder.createMessage(new byte[0], unsubscribe.getMessageHeaders()));
        realtimeSessionManager.unregisterSubscription(subscription.sessionId(), subscription.subscriptionId());

        // Addressed to the one connection that held the subscription, not every tab of the user.
        SimpMessageHeaderAccessor notice = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        notice.setSessionId(subscription.sessionId());
        notice.setLeaveMutable(true);
        simpMessagingTemplate.convertAndSendToUser(
                subscription.principalName(),
                SESSION_QUEUE,
                new RealtimeRevocationResponse(
                        "SUBSCRIPTION_REVOKED",
                        reason,
                        subscription.destination(),
                        subscription.serverId(),
                        subscription.boardId()),
                notice.getMessageHeaders());
    }
}
