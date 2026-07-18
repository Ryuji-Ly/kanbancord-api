package com.kanbancord_api.realtime;

import com.kanbancord_api.dto.RealtimeSessionResponse;
import com.kanbancord_api.dto.RealtimeSubscriptionResponse;
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

        RealtimeSessionResponse response = new RealtimeSessionResponse();
        response.setType("SESSION_PONG");
        response.setSessionId(snapshot.sessionId());
        response.setUserId(snapshot.userId());
        response.setConnectedAt(snapshot.connectedAt());
        response.setLastSeenAt(snapshot.lastSeenAt());
        response.setServerTime(Instant.now());
        response.setAllowedSendDestinations(snapshot.allowedSendDestinations());
        response.setSubscriptions(snapshot.subscriptions().stream().map(this::mapSubscription).toList());
        return response;
    }

    private RealtimeSubscriptionResponse mapSubscription(RealtimeSessionManager.SubscriptionSnapshot snapshot) {
        RealtimeSubscriptionResponse response = new RealtimeSubscriptionResponse();
        response.setSubscriptionId(snapshot.subscriptionId());
        response.setDestination(snapshot.destination());
        response.setScopeType(snapshot.scopeType());
        response.setServerId(snapshot.serverId());
        response.setBoardId(snapshot.boardId());
        response.setSubscribedAt(snapshot.subscribedAt());
        return response;
    }

    private Long resolveUserId(Principal principal) {
        if (principal instanceof RealtimeUserPrincipal realtimeUserPrincipal) {
            return realtimeUserPrincipal.getUserId();
        }
        return Long.parseLong(principal.getName());
    }
}