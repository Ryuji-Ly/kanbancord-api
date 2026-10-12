package com.kanbancord_api.auth;

import com.kanbancord_api.security.JwtTokenService;
import com.kanbancord_api.session.UserSession;
import com.kanbancord_api.session.UserSessionService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserResponse;
import org.springframework.stereotype.Service;

/**
 * Signing in with a Discord authorization code, shared by the website and the Discord Activity:
 * the code is exchanged, the user's profile synced, their Discord tokens stored and a session started.
 */
@Service
public class DiscordSignInService {

    private final DiscordIdentityService discordIdentityService;
    private final DiscordOAuthService discordOAuthService;
    private final DiscordCredentialService discordCredentialService;
    private final JwtTokenService jwtTokenService;
    private final UserSessionService userSessionService;

    public DiscordSignInService(
            DiscordIdentityService discordIdentityService,
            DiscordOAuthService discordOAuthService,
            DiscordCredentialService discordCredentialService,
            JwtTokenService jwtTokenService,
            UserSessionService userSessionService) {
        this.discordIdentityService = discordIdentityService;
        this.discordOAuthService = discordOAuthService;
        this.discordCredentialService = discordCredentialService;
        this.jwtTokenService = jwtTokenService;
        this.userSessionService = userSessionService;
    }

    public record SignedIn(User user, UserSessionService.IssuedSession issued) {
    }

    /** {@code redirectUri} is null for codes the Activity got from the Embedded App SDK, which uses none. */
    public SignedIn signIn(String code, String redirectUri, String userAgent) {
        DiscordOAuthService.DiscordTokens tokens = discordOAuthService.exchangeCode(code, redirectUri);
        User user = discordIdentityService.authenticateAndSyncUser(tokens.accessToken());
        discordCredentialService.store(user.getUserId(), tokens);
        return new SignedIn(user, userSessionService.start(user.getUserId(), userAgent));
    }

    public AuthResponse authResponse(User user, UserSession session) {
        return authResponse(user, session, false);
    }

    /** {@code activity}: the session is the Discord Activity's, so its changes are recorded as such. */
    public AuthResponse authResponse(User user, UserSession session, boolean activity) {
        return new AuthResponse(
                "Bearer",
                jwtTokenService.issueToken(user.getUserId(), session.getSessionId(), activity),
                jwtTokenService.getExpirationSeconds(),
                new UserResponse(
                        user.getUserId(),
                        user.getUsername(),
                        user.getGlobalName(),
                        user.getAvatarUrl(),
                        user.getPreferences(),
                        user.getCreatedAt(),
                        user.getUpdatedAt()),
                session.getSessionId().toString());
    }
}
