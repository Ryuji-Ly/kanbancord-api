package com.kanbancord_api.auth;

import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.session.SessionCookies;
import com.kanbancord_api.session.UserSessionService;
import com.kanbancord_api.user.User;
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

    private final DiscordSignInService discordSignInService;
    private final UserSessionService userSessionService;
    private final UserService userService;
    private final SessionCookies sessionCookies;

    public AuthController(
            DiscordSignInService discordSignInService,
            UserSessionService userSessionService,
            UserService userService,
            SessionCookies sessionCookies) {
        this.discordSignInService = discordSignInService;
        this.userSessionService = userSessionService;
        this.userService = userService;
        this.sessionCookies = sessionCookies;
    }

    @PostMapping("/discord/exchange")
    public ResponseEntity<AuthResponse> exchangeCodeAndLogin(
            @Valid @RequestBody DiscordOAuthCodeExchangeRequest request,
            HttpServletRequest httpRequest) {
        sessionCookies.requireAllowedOrigin(httpRequest);
        DiscordSignInService.SignedIn signedIn = discordSignInService.signIn(
                request.code(), request.redirectUri(), httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.set(signedIn.issued().refreshToken()))
                .body(discordSignInService.authResponse(signedIn.user(), signedIn.issued().session()));
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
            return response.body(discordSignInService.authResponse(user, refreshed.session()));
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
}
