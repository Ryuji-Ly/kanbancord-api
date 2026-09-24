package com.kanbancord_api.realtime;

import com.kanbancord_api.session.UserSessionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_PRINCIPAL = "realtime.principal";

    private final RealtimeTicketService realtimeTicketService;
    private final UserSessionService userSessionService;

    public RealtimeHandshakeInterceptor(
            RealtimeTicketService realtimeTicketService,
            UserSessionService userSessionService) {
        this.realtimeTicketService = realtimeTicketService;
        this.userSessionService = userSessionService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        String ticket = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("ticket");

        Optional<RealtimeTicketService.ValidatedTicket> validatedTicket = realtimeTicketService.consumeTicket(ticket)
                // The session may have been signed out since the ticket was issued.
                .filter(valid -> userSessionService.isActive(valid.sessionId(), valid.userId()));
        if (validatedTicket.isEmpty()) {
            if (response instanceof ServletServerHttpResponse servletResponse) {
                servletResponse.getServletResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
            return false;
        }

        RealtimeUserPrincipal principal = new RealtimeUserPrincipal(
                validatedTicket.get().userId(), validatedTicket.get().sessionId());
        attributes.put(ATTR_PRINCIPAL, principal);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }
}