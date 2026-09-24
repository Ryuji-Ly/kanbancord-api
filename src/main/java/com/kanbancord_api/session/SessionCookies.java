package com.kanbancord_api.session;

import com.kanbancord_api.config.CorsProperties;
import com.kanbancord_api.exception.AccessDeniedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refresh-token cookie. It is httpOnly, so scripts on the page cannot read it, and scoped to the
 * auth endpoints, so it is sent only to sign in, refresh and sign out.
 *
 * <p>Requests that use the cookie must come from one of the web app's origins. Together with
 * {@code SameSite} this stops other sites from refreshing or ending a user's session.
 */
@Component
public class SessionCookies {

    static final String COOKIE_PATH = "/api/auth";

    private final SessionProperties sessionProperties;
    private final CorsProperties corsProperties;

    public SessionCookies(SessionProperties sessionProperties, CorsProperties corsProperties) {
        this.sessionProperties = sessionProperties;
        this.corsProperties = corsProperties;
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (sessionProperties.getCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public String set(String refreshToken) {
        return base(refreshToken).maxAge(Duration.ofDays(sessionProperties.getRefreshTtlDays())).build().toString();
    }

    public String clear() {
        return base("").maxAge(0).build().toString();
    }

    public void requireAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !corsProperties.getAllowedOrigins().contains(origin)) {
            throw new AccessDeniedException("Requests that use the session cookie must come from the KanbanCord web app");
        }
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(sessionProperties.getCookieName(), value)
                .httpOnly(true)
                .secure(sessionProperties.isCookieSecure())
                .sameSite(sessionProperties.getCookieSameSite())
                .path(COOKIE_PATH);
    }
}
