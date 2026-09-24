package com.kanbancord_api.realtime;

import com.kanbancord_api.session.SessionsRevokedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open WebSocket connections, so they can be closed when the sign-in session they were opened
 * with is revoked. The client then fails to get a new ticket and is signed out.
 */
@Component
public class RealtimeConnectionRegistry implements WebSocketHandlerDecoratorFactory {

    /** Application close code (4000-4999): the sign-in session behind this connection was revoked. */
    public static final CloseStatus SESSION_REVOKED = new CloseStatus(4401, "Session revoked");

    private static final Logger log = LoggerFactory.getLogger(RealtimeConnectionRegistry.class);

    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                connections.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                connections.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    @TransactionalEventListener
    public void onSessionsRevoked(SessionsRevokedEvent event) {
        connections.values().forEach(connection -> {
            if (connection.getPrincipal() instanceof RealtimeUserPrincipal principal
                    && event.sessionIds().contains(principal.getAuthSessionId())) {
                close(connection);
            }
        });
    }

    private static void close(WebSocketSession connection) {
        try {
            connection.close(SESSION_REVOKED);
        } catch (IOException ex) {
            log.debug("Failed to close realtime connection {}", connection.getId(), ex);
        }
    }
}
