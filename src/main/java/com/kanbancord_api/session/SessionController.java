package com.kanbancord_api.session;

import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.security.AuthenticatedSession;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The signed-in user's sessions: listing them and signing out others. */
@RestController
@RequestMapping("/api/me/sessions")
public class SessionController {

    private final UserSessionService userSessionService;

    public SessionController(UserSessionService userSessionService) {
        this.userSessionService = userSessionService;
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> list(@CurrentUser Long userId) {
        UUID current = currentSessionId();
        List<SessionResponse> sessions = userSessionService.activeSessions(userId).stream()
                .map(session -> new SessionResponse(
                        session.getSessionId(),
                        session.getCreatedAt(),
                        session.getLastUsedAt(),
                        session.getUserAgent(),
                        session.getSessionId().equals(current)))
                .toList();
        return ResponseEntity.ok(sessions);
    }

    /** Signs out every other session, for example after signing in on a shared computer. */
    @DeleteMapping
    public ResponseEntity<Void> revokeOthers(@CurrentUser Long userId) {
        userSessionService.revokeOthers(userId, currentSessionId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@CurrentUser Long userId, @PathVariable UUID sessionId) {
        userSessionService.revoke(userId, sessionId);
        return ResponseEntity.noContent().build();
    }

    private static UUID currentSessionId() {
        return AuthenticatedSession.currentSessionId().orElseThrow(UnauthenticatedException::new);
    }
}
