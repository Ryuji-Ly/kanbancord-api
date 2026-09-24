package com.kanbancord_api.realtime;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeTicketService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RealtimeProperties realtimeProperties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PendingTicket> pendingTickets = new ConcurrentHashMap<>();

    public RealtimeTicketService(RealtimeProperties realtimeProperties) {
        this.realtimeProperties = realtimeProperties;
    }

    public IssuedTicket issueTicket(Long userId) {
        cleanupExpired();

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        Instant expiresAt = Instant.now().plusSeconds(realtimeProperties.getTicketTtlSeconds());
        String ticket = URL_ENCODER.encodeToString(randomBytes);
        pendingTickets.put(ticket, new PendingTicket(userId, expiresAt));

        return new IssuedTicket(ticket, expiresAt);
    }

    public Optional<ValidatedTicket> consumeTicket(String rawTicket) {
        cleanupExpired();

        if (rawTicket == null || rawTicket.isBlank()) {
            return Optional.empty();
        }

        PendingTicket pendingTicket = pendingTickets.remove(rawTicket.trim());
        if (pendingTicket == null || pendingTicket.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }

        return Optional.of(new ValidatedTicket(pendingTicket.userId(), pendingTicket.expiresAt()));
    }

    private void cleanupExpired() {
        if (pendingTickets.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        pendingTickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record PendingTicket(Long userId, Instant expiresAt) {
    }

    public record IssuedTicket(String ticket, Instant expiresAt) {
    }

    public record ValidatedTicket(Long userId, Instant expiresAt) {
    }
}