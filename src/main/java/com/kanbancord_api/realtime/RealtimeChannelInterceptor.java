package com.kanbancord_api.realtime;

import com.kanbancord_api.exception.AccessDeniedException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
public class RealtimeChannelInterceptor implements ChannelInterceptor {

    private final RealtimeDestinationAuthorizationService realtimeDestinationAuthorizationService;
    private final RealtimeSessionManager realtimeSessionManager;

    public RealtimeChannelInterceptor(
            RealtimeDestinationAuthorizationService realtimeDestinationAuthorizationService,
            RealtimeSessionManager realtimeSessionManager) {
        this.realtimeDestinationAuthorizationService = realtimeDestinationAuthorizationService;
        this.realtimeSessionManager = realtimeSessionManager;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT -> handleConnect(accessor);
            case SUBSCRIBE -> handleSubscribe(accessor);
            case UNSUBSCRIBE -> realtimeSessionManager.unregisterSubscription(
                    accessor.getSessionId(),
                    accessor.getSubscriptionId());
            case SEND -> handleSend(accessor);
            case DISCONNECT -> realtimeSessionManager.unregisterSession(accessor.getSessionId());
            default -> realtimeSessionManager.touch(accessor.getSessionId());
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        Principal principal = resolvePrincipal(accessor);
        Long userId = resolveUserId(principal);

        realtimeSessionManager.registerConnection(
                accessor.getSessionId(),
                userId,
                principal.getName(),
                realtimeDestinationAuthorizationService.getAllowedSendDestinations());
        accessor.setUser(principal);
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        Principal principal = resolvePrincipal(accessor);
        Long userId = resolveUserId(principal);

        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new AccessDeniedException("A subscription id is required");
        }

        RealtimeDestinationAuthorizationService.AuthorizedSubscription authorizedSubscription = realtimeDestinationAuthorizationService
                .authorizeSubscribe(userId, accessor.getDestination())
                .withSubscriptionId(subscriptionId);

        realtimeSessionManager.registerSubscription(accessor.getSessionId(), authorizedSubscription);
    }

    private void handleSend(StompHeaderAccessor accessor) {
        Principal principal = resolvePrincipal(accessor);
        Long userId = resolveUserId(principal);
        realtimeDestinationAuthorizationService.authorizeSend(userId, accessor.getDestination());
        realtimeSessionManager.touch(accessor.getSessionId());
    }

    private Principal resolvePrincipal(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal != null) {
            return principal;
        }

        Object fromAttributes = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(RealtimeHandshakeInterceptor.ATTR_PRINCIPAL);
        if (fromAttributes instanceof Principal attributePrincipal) {
            return attributePrincipal;
        }

        throw new AccessDeniedException("Realtime authentication is required");
    }

    private Long resolveUserId(Principal principal) {
        if (principal instanceof RealtimeUserPrincipal realtimeUserPrincipal) {
            return realtimeUserPrincipal.getUserId();
        }

        try {
            return Long.parseLong(principal.getName());
        } catch (NumberFormatException ex) {
            throw new AccessDeniedException("Realtime principal is invalid");
        }
    }
}