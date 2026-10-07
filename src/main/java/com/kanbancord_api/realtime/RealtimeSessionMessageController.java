package com.kanbancord_api.realtime;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;

@Controller
public class RealtimeSessionMessageController {

    private final RealtimeDestinationAuthorizationService realtimeDestinationAuthorizationService;
    private final RealtimeSessionManager realtimeSessionManager;

    public RealtimeSessionMessageController(
            RealtimeDestinationAuthorizationService realtimeDestinationAuthorizationService,
            RealtimeSessionManager realtimeSessionManager) {
        this.realtimeDestinationAuthorizationService = realtimeDestinationAuthorizationService;
        this.realtimeSessionManager = realtimeSessionManager;
    }

    @MessageMapping("/session/ping")
    @SendToUser("/queue/session")
    public RealtimeSessionResponse ping(Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        RealtimeSessionManager.SessionSnapshot snapshot = realtimeSessionManager.snapshot(sessionId)
                .orElseGet(() -> realtimeSessionManager.registerConnection(
                        sessionId,
                        resolveUserId(principal),
                        principal.getName(),
                        realtimeDestinationAuthorizationService.getAllowedSendDestinations()));

        return new RealtimeSessionResponse(
                "SESSION_PONG",
                snapshot.sessionId(),
                snapshot.userId(),
                snapshot.connectedAt(),
                snapshot.lastSeenAt(),
                Instant.now(),
                snapshot.allowedSendDestinations(),
                snapshot.subscriptions().stream().map(this::mapSubscription).toList());
    }

    private RealtimeSubscriptionResponse mapSubscription(RealtimeSessionManager.SubscriptionSnapshot snapshot) {
        return new RealtimeSubscriptionResponse(
                snapshot.subscriptionId(),
                snapshot.destination(),
                snapshot.scopeType(),
                snapshot.serverId(),
                snapshot.boardId(),
                snapshot.subscribedAt());
    }

    private Long resolveUserId(Principal principal) {
        if (principal instanceof RealtimeUserPrincipal realtimeUserPrincipal) {
            return realtimeUserPrincipal.getUserId();
        }
        return Long.parseLong(principal.getName());
    }
}