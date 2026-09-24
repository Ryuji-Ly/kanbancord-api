package com.kanbancord_api.auth;

import com.kanbancord_api.exception.DiscordReauthorizationRequiredException;
import com.kanbancord_api.security.TokenCipher;
import com.kanbancord_api.session.SessionsRevokedEvent;
import com.kanbancord_api.session.UserSessionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Keeps each user's Discord OAuth tokens, encrypted, so the API can call Discord for them and the
 * browser never holds a Discord token. Tokens are kept only while the user has an active session.
 */
@Service
public class DiscordCredentialService {

    /** Refresh this long before the access token expires, so a request never uses one that is about to lapse. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);

    private final DiscordCredentialRepository discordCredentialRepository;
    private final DiscordOAuthService discordOAuthService;
    private final TokenCipher tokenCipher;
    private final UserSessionService userSessionService;
    private final ApplicationEventPublisher eventPublisher;

    public DiscordCredentialService(
            DiscordCredentialRepository discordCredentialRepository,
            DiscordOAuthService discordOAuthService,
            TokenCipher tokenCipher,
            UserSessionService userSessionService,
            ApplicationEventPublisher eventPublisher) {
        this.discordCredentialRepository = discordCredentialRepository;
        this.discordOAuthService = discordOAuthService;
        this.tokenCipher = tokenCipher;
        this.userSessionService = userSessionService;
        this.eventPublisher = eventPublisher;
    }

    /** Published when a user's Discord tokens are deleted, so anything cached with them is dropped. */
    public record DiscordCredentialsForgotten(Long userId) {
    }

    @Transactional
    public void store(Long userId, DiscordOAuthService.DiscordTokens tokens) {
        DiscordCredential credential = discordCredentialRepository.findForUpdate(userId).orElseGet(DiscordCredential::new);
        credential.setUserId(userId);
        apply(credential, tokens);
        discordCredentialRepository.save(credential);
    }

    /**
     * A current Discord access token for the user, refreshed first when it is about to expire. The
     * row stays locked until the transaction ends, so concurrent callers refresh only once.
     */
    @Transactional(noRollbackFor = DiscordReauthorizationRequiredException.class)
    public String accessToken(Long userId) {
        DiscordCredential credential = discordCredentialRepository.findForUpdate(userId)
                .orElseThrow(DiscordReauthorizationRequiredException::new);
        String owner = owner(userId);

        try {
            if (credential.getExpiresAt().isAfter(Instant.now().plus(EXPIRY_MARGIN))) {
                return tokenCipher.decrypt(credential.getAccessToken(), owner);
            }
            if (credential.getRefreshToken() == null) {
                throw new DiscordOAuthService.DiscordAuthorizationRevokedException();
            }
            DiscordOAuthService.DiscordTokens tokens =
                    discordOAuthService.refresh(tokenCipher.decrypt(credential.getRefreshToken(), owner));
            apply(credential, tokens);
            return tokens.accessToken();
        } catch (DiscordOAuthService.DiscordAuthorizationRevokedException | TokenCipher.UndecryptableTokenException ex) {
            // Revoked at Discord, or encrypted with a key that has since changed: only a new sign-in helps.
            forget(credential);
            throw new DiscordReauthorizationRequiredException();
        }
    }

    /** Discord rejected the user's access token, for example because they removed the app. */
    @Transactional
    public void rejected(Long userId) {
        discordCredentialRepository.findForUpdate(userId).ifPresent(this::forget);
    }

    /** Once a user's last session ends, their Discord tokens are deleted and revoked at Discord. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSessionsRevoked(SessionsRevokedEvent event) {
        if (userSessionService.hasActiveSession(event.userId())) {
            return;
        }
        Optional<DiscordCredential> credential = discordCredentialRepository.findForUpdate(event.userId());
        if (credential.isEmpty()) {
            return;
        }
        byte[] refreshToken = credential.get().getRefreshToken();
        byte[] revocable = refreshToken != null ? refreshToken : credential.get().getAccessToken();
        forget(credential.get());
        try {
            // Revoking the refresh token also revokes the access tokens issued with it.
            discordOAuthService.revoke(tokenCipher.decrypt(revocable, owner(event.userId())));
        } catch (TokenCipher.UndecryptableTokenException ex) {
            // Nothing to revoke with; the stored copy is gone either way.
        }
    }

    private void forget(DiscordCredential credential) {
        discordCredentialRepository.delete(credential);
        eventPublisher.publishEvent(new DiscordCredentialsForgotten(credential.getUserId()));
    }

    private void apply(DiscordCredential credential, DiscordOAuthService.DiscordTokens tokens) {
        String owner = owner(credential.getUserId());
        credential.setAccessToken(tokenCipher.encrypt(tokens.accessToken(), owner));
        if (tokens.refreshToken() != null) {
            credential.setRefreshToken(tokenCipher.encrypt(tokens.refreshToken(), owner));
        }
        credential.setExpiresAt(tokens.expiresAt());
        credential.setScope(tokens.scope());
        credential.setUpdatedAt(Instant.now());
    }

    private static String owner(Long userId) {
        return "discord-credential:" + userId;
    }
}
