package com.kanbancord_api.auth;

import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.session.UserSessionService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in from the Discord Activity, where the web app runs inside Discord.
 *
 * <p>The Activity is a page inside Discord's own frame, served through Discord's proxy, where the
 * website's refresh cookie cannot be relied on. The refresh token is therefore returned in the body
 * and the Activity keeps it in memory only, for as long as it is open. Nothing here reads a cookie,
 * so other sites gain nothing by calling these endpoints.
 */
@RestController
@RequestMapping("/api/auth/activity")
@Validated
public class ActivityAuthController {

    /** Shown in the user's list of sessions, so they can tell this one apart. */
    static final String SESSION_LABEL = "Discord Activity";

    private final DiscordSignInService discordSignInService;
    private final UserSessionService userSessionService;
    private final UserService userService;

    public ActivityAuthController(
            DiscordSignInService discordSignInService,
            UserSessionService userSessionService,
            UserService userService) {
        this.discordSignInService = discordSignInService;
        this.userSessionService = userSessionService;
        this.userService = userService;
    }

    public record ExchangeRequest(@NotBlank String code) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** An {@link AuthResponse} plus the refresh token, which the website gets as a cookie instead. */
    public record ActivityAuthResponse(AuthResponse session, String refreshToken) {
    }

    /** Completes a sign-in with the code the Embedded App SDK's {@code authorize} returned. */
    @PostMapping("/exchange")
    public ActivityAuthResponse exchange(@Valid @RequestBody ExchangeRequest request, HttpServletRequest httpRequest) {
        DiscordSignInService.SignedIn signedIn =
                discordSignInService.signIn(request.code(), null, sessionLabel(httpRequest));
        return new ActivityAuthResponse(
                discordSignInService.authResponse(signedIn.user(), signedIn.issued().session()),
                signedIn.issued().refreshToken());
    }

    /**
     * A new access token, and the rotated refresh token. When the token presented was replaced moments
     * ago and the current one cannot be resent, {@code refreshToken} is null and the old one stays valid
     * until the rotation grace period ends.
     */
    @PostMapping("/refresh")
    public ActivityAuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        UserSessionService.RefreshedSession refreshed = userSessionService.refresh(request.refreshToken());
        User user = userService.findById(refreshed.session().getUserId())
                .orElseThrow(() -> new UnauthenticatedException("Session expired"));
        return new ActivityAuthResponse(
                discordSignInService.authResponse(user, refreshed.session()),
                refreshed.refreshToken());
    }

    /** Signs out the Activity's session, when the user signs out inside it. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        userSessionService.revokeByRefreshToken(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static String sessionLabel(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return userAgent == null || userAgent.isBlank() ? SESSION_LABEL : SESSION_LABEL + " · " + userAgent;
    }
}
