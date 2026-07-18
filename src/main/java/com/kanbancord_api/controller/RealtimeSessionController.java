package com.kanbancord_api.controller;

import com.kanbancord_api.dto.RealtimeTicketResponse;
import com.kanbancord_api.realtime.RealtimeTicketService;
import com.kanbancord_api.service.AccessValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime")
public class RealtimeSessionController {

    private final AccessValidator accessValidator;
    private final RealtimeTicketService realtimeTicketService;

    public RealtimeSessionController(
            AccessValidator accessValidator,
            RealtimeTicketService realtimeTicketService) {
        this.accessValidator = accessValidator;
        this.realtimeTicketService = realtimeTicketService;
    }

    @PostMapping("/tickets")
    public ResponseEntity<RealtimeTicketResponse> createTicket() {
        Long userId = accessValidator.requireAuthenticatedUserId();
        RealtimeTicketService.IssuedTicket issuedTicket = realtimeTicketService.issueTicket(userId);

        RealtimeTicketResponse response = new RealtimeTicketResponse();
        response.setTicket(issuedTicket.ticket());
        response.setExpiresAt(issuedTicket.expiresAt());
        response.setWebsocketPath("/ws");

        return ResponseEntity.ok(response);
    }
}