package com.kanbancord_api.auth;

import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.security.JwtTokenService;
import com.kanbancord_api.session.SessionCookies;
import com.kanbancord_api.session.UserSession;
import com.kanbancord_api.session.UserSessionService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserResponse;
import com.kanbancord_api.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in with Discord, refreshing the short-lived access token, and signing out.
 *
 * <p>The access token is returned in the body and kept in memory by the web app. The refresh token
 * is only ever sent as an httpOnly cookie.
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final DiscordIdentityService discordIdentityService;
    private final DiscordOAuthService discordOAuthService;
    private final DiscordCredentialService discordCredentialService;
    private final JwtTokenService jwtTokenService;
    private final UserSessionService userSessionService;
    private final UserService userService;
    private final SessionCookies sessionCookies;

    public AuthController(
            DiscordIdentityService discordIdentityService,
            DiscordOAuthService discordOAuthService,
            DiscordCredentialService discordCredentialService,
            JwtTokenService jwtTokenService,
            UserSessionService userSessionService,
            UserService userService,
            SessionCookies sessionCookies) {
        this.discordIdentityService = discordIdentityService;
        this.discordOAuthService = discordOAuthService;
        this.discordCredentialService = discordCredentialService;
        this.jwtTokenService = jwtTokenService;
        this.userSessionService = userSessionService;
        this.userService = userService;
        this.sessionCookies = sessionCookies;
    }

    @PostMapping("/discord/exchange")
    public ResponseEntity<AuthResponse> exchangeCodeAndLogin(
            @Valid @RequestBody DiscordOAuthCodeExchangeRequest request,
            HttpServletRequest httpRequest) {
        sessionCookies.requireAllowedOrigin(httpRequest);
        DiscordOAuthService.DiscordTokens tokens =
                discordOAuthService.exchangeCode(request.code(), request.redirectUri());
        User user = discordIdentityService.authenticateAndSyncUser(tokens.accessToken());
        discordCredentialService.store(user.getUserId(), tokens);

        UserSessionService.IssuedSession issued =
                userSessionService.start(user.getUserId(), httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.set(issued.refreshToken()))
                .body(toAuthResponse(user, issued.session()));
    }

    /** A new access token for the session in the refresh cookie; the cookie is rotated at the same time. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest httpRequest) {
        sessionCookies.requireAllowedOrigin(httpRequest);
        try {
            UserSessionService.RefreshedSession refreshed = userSessionService.refresh(sessionCookies.read(httpRequest));
            User user = userService.findById(refreshed.session().getUserId())
                    .orElseThrow(() -> new UnauthenticatedException("Session expired"));

            ResponseEntity.BodyBuilder response = ResponseEntity.ok();
            if (refreshed.refreshToken() != null) {
                response.header(HttpHeaders.SET_COOKIE, sessionCookies.set(refreshed.refreshToken()));
            }
            return response.body(toAuthResponse(user, refreshed.session()));
        } catch (UnauthenticatedException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, sessionCookies.clear())
                    .build();
        }
    }

    /** Signs out the session in the refresh cookie. Needs no access token, so it works after one expires. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        sessionCookies.requireAllowedOrigin(httpRequest);
        userSessionService.revokeByRefreshToken(sessionCookies.read(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.clear())
                .build();
    }

    private AuthResponse toAuthResponse(User user, UserSession session) {
        return new AuthResponse(
                "Bearer",
                jwtTokenService.issueToken(user.getUserId(), session.getSessionId()),
                jwtTokenService.getExpirationSeconds(),
                toUserResponse(user),
                session.getSessionId().toString());
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getUsername(),
                user.getGlobalName(),
                user.getAvatarUrl(),
                user.getPreferences(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
