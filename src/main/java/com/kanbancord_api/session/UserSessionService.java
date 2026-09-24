package com.kanbancord_api.session;

import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.security.AuthJwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Sign-in sessions: starting one at login, rotating its refresh token, and revoking it.
 *
 * <p>Refresh tokens rotate on every use. A token that was just replaced is accepted for a short
 * grace period, because several tabs may refresh with it at once, and because a browser can lose the
 * response that carried the replacement (a reload mid-request). Presenting it later means it was
 * copied, so the session is revoked.
 *
 * <p>Each replacement is derived from the token it replaces with a server-side key. That lets a
 * refresh in the grace period hand the browser the current token again without storing it: only
 * token hashes are kept.
 */
@Service
public class UserSessionService {

    private static final Logger log = LoggerFactory.getLogger(UserSessionService.class);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** How long "is this session still active" is remembered; revocations on this instance evict it at once. */
    private static final Duration ACTIVE_CACHE_TTL = Duration.ofSeconds(30);
    private static final int USER_AGENT_MAX_LENGTH = 512;
    /** Ended sessions are kept this long, then deleted. */
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final String ROTATION_LABEL = "kanbancord/refresh-rotation/v1";

    private final UserSessionRepository userSessionRepository;
    private final SessionProperties sessionProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthJwtProperties authJwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<UUID, CachedState> activeCache = new ConcurrentHashMap<>();

    public UserSessionService(
            UserSessionRepository userSessionRepository,
            SessionProperties sessionProperties,
            ApplicationEventPublisher eventPublisher,
            AuthJwtProperties authJwtProperties) {
        this.userSessionRepository = userSessionRepository;
        this.sessionProperties = sessionProperties;
        this.eventPublisher = eventPublisher;
        this.authJwtProperties = authJwtProperties;
    }

    /** A session and the raw refresh token to hand to the browser. */
    public record IssuedSession(UserSession session, String refreshToken) {
    }

    /**
     * The session a refresh token belongs to, and the current refresh token to hand to the browser.
     * {@code refreshToken} is null only when the current token cannot be re-derived, after the
     * server's key changed.
     */
    public record RefreshedSession(UserSession session, String refreshToken) {
    }

    @Transactional
    public IssuedSession start(Long userId, String userAgent) {
        Instant now = Instant.now();
        String refreshToken = newRefreshToken();

        UserSession session = new UserSession();
        session.setSessionId(UUID.randomUUID());
        session.setUserId(userId);
        session.setRefreshTokenHash(hash(refreshToken));
        session.setCreatedAt(now);
        session.setRefreshedAt(now);
        session.setLastUsedAt(now);
        session.setExpiresAt(now.plus(refreshTtl()));
        session.setUserAgent(truncate(userAgent));
        return new IssuedSession(userSessionRepository.save(session), refreshToken);
    }

    /** Rotates the refresh token. Committed even when it fails, so a detected token theft stays revoked. */
    @Transactional(noRollbackFor = UnauthenticatedException.class)
    public RefreshedSession refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthenticatedException("Not signed in");
        }
        Instant now = Instant.now();
        String presentedHash = hash(refreshToken);

        Optional<UserSession> current = userSessionRepository.findByRefreshTokenHash(presentedHash);
        if (current.isPresent()) {
            UserSession session = requireActive(current.get(), now);
            String replacement = successor(session, refreshToken);
            session.setPreviousRefreshTokenHash(session.getRefreshTokenHash());
            session.setRefreshTokenHash(hash(replacement));
            session.setRefreshedAt(now);
            session.setLastUsedAt(now);
            session.setExpiresAt(now.plus(refreshTtl()));
            return new RefreshedSession(session, replacement);
        }

        UserSession replaced = userSessionRepository.findByPreviousRefreshTokenHash(presentedHash)
                .orElseThrow(() -> new UnauthenticatedException("Session expired"));
        requireActive(replaced, now);
        if (replaced.getRefreshedAt().plusSeconds(sessionProperties.getRotationGraceSeconds()).isAfter(now)) {
            replaced.setLastUsedAt(now);
            // Resend the current token, in case the response that carried it never arrived.
            String currentToken = successor(replaced, refreshToken);
            return new RefreshedSession(replaced,
                    hash(currentToken).equals(replaced.getRefreshTokenHash()) ? currentToken : null);
        }

        log.warn("Refresh token reused after rotation; revoking session {} of user {}",
                replaced.getSessionId(), replaced.getUserId());
        markRevoked(List.of(replaced), now);
        throw new UnauthenticatedException("Session expired");
    }

    /** Signs out the session a refresh token belongs to, if any. */
    @Transactional
    public void revokeByRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String presentedHash = hash(refreshToken);
        userSessionRepository.findByRefreshTokenHash(presentedHash)
                .or(() -> userSessionRepository.findByPreviousRefreshTokenHash(presentedHash))
                .filter(session -> session.getRevokedAt() == null)
                .ifPresent(session -> markRevoked(List.of(session), Instant.now()));
    }

    /** Signs out one of the user's own sessions. */
    @Transactional
    public void revoke(Long userId, UUID sessionId) {
        Instant now = Instant.now();
        UserSession session = userSessionRepository.findById(sessionId)
                .filter(candidate -> candidate.getUserId().equals(userId) && candidate.isActive(now))
                .orElseThrow(() -> new ResourceNotFoundException("Session", "sessionId", sessionId));
        markRevoked(List.of(session), now);
    }

    /** Signs out every session of the user except {@code keep}. */
    @Transactional
    public void revokeOthers(Long userId, UUID keep) {
        Instant now = Instant.now();
        List<UserSession> others = userSessionRepository.findActiveByUserId(userId, now).stream()
                .filter(session -> !session.getSessionId().equals(keep))
                .toList();
        markRevoked(others, now);
    }

    @Transactional(readOnly = true)
    public List<UserSession> activeSessions(Long userId) {
        return userSessionRepository.findActiveByUserId(userId, Instant.now());
    }

    @Transactional(readOnly = true)
    public boolean hasActiveSession(Long userId) {
        return userSessionRepository.existsActiveByUserId(userId, Instant.now());
    }

    /** Whether an access token naming this session and user may still be used. Checked on every request. */
    public boolean isActive(UUID sessionId, Long userId) {
        Instant now = Instant.now();
        CachedState cached = activeCache.get(sessionId);
        if (cached == null || cached.checkedAt().plus(ACTIVE_CACHE_TTL).isBefore(now)) {
            cached = userSessionRepository.findById(sessionId)
                    .map(session -> new CachedState(session.getUserId(),
                            session.getRevokedAt() == null ? session.getExpiresAt() : Instant.MIN, now))
                    .orElse(new CachedState(null, Instant.MIN, now));
            activeCache.put(sessionId, cached);
        }
        return userId.equals(cached.userId()) && cached.activeUntil().isAfter(now);
    }

    /** Runs once the revocation has committed, so no request can cache the session as active again. */
    @TransactionalEventListener
    public void onSessionsRevoked(SessionsRevokedEvent event) {
        event.sessionIds().forEach(activeCache::remove);
    }

    @Scheduled(cron = "0 17 4 * * *")
    @Transactional
    public void deleteEndedSessions() {
        int deleted = userSessionRepository.deleteEndedBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("Deleted {} ended sessions", deleted);
        }
        activeCache.clear();
    }

    private UserSession requireActive(UserSession session, Instant now) {
        if (!session.isActive(now)) {
            throw new UnauthenticatedException("Session expired");
        }
        return session;
    }

    private void markRevoked(List<UserSession> sessions, Instant now) {
        if (sessions.isEmpty()) {
            return;
        }
        sessions.forEach(session -> session.setRevokedAt(now));
        Set<UUID> ids = sessions.stream().map(UserSession::getSessionId).collect(Collectors.toSet());
        eventPublisher.publishEvent(new SessionsRevokedEvent(sessions.get(0).getUserId(), ids));
    }

    private Duration refreshTtl() {
        return Duration.ofDays(sessionProperties.getRefreshTtlDays());
    }

    /** The token that replaces {@code refreshToken}: an HMAC of it, so only this server can compute it. */
    private String successor(UserSession session, String refreshToken) {
        String secret = authJwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            return newRefreshToken();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((ROTATION_LABEL + ":" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(session.getSessionId().toString().getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(mac.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to derive a refresh token", ex);
        }
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    static String hash(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() <= USER_AGENT_MAX_LENGTH ? userAgent : userAgent.substring(0, USER_AGENT_MAX_LENGTH);
    }

    private record CachedState(Long userId, Instant activeUntil, Instant checkedAt) {
    }
}
