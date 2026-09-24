package com.kanbancord_api.realtime;

import com.kanbancord_api.access.Authorizer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime")
public class RealtimeSessionController {

    private final Authorizer authorizer;
    private final RealtimeTicketService realtimeTicketService;

    public RealtimeSessionController(
            Authorizer authorizer,
            RealtimeTicketService realtimeTicketService) {
        this.authorizer = authorizer;
        this.realtimeTicketService = realtimeTicketService;
    }

    @PostMapping("/tickets")
    public ResponseEntity<RealtimeTicketResponse> createTicket() {
        Long userId = authorizer.requireAuthenticatedUserId();
        RealtimeTicketService.IssuedTicket issuedTicket = realtimeTicketService.issueTicket(userId);

        RealtimeTicketResponse response = new RealtimeTicketResponse();
        response.setTicket(issuedTicket.ticket());
        response.setExpiresAt(issuedTicket.expiresAt());
        response.setWebsocketPath("/ws");

        return ResponseEntity.ok(response);
    }
}