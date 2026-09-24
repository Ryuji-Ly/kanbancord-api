package com.kanbancord_api.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** How long sign-ins last and how the refresh cookie is set. */
@Component
@ConfigurationProperties(prefix = "kanbancord.auth.session")
public class SessionProperties {

    /** A session ends after this many days without a refresh. */
    private long refreshTtlDays = 30;
    /** How long a refresh token that was just replaced is still accepted, for tabs refreshing at once. */
    private long rotationGraceSeconds = 60;
    private String cookieName = "kc_refresh";
    /** False only for local development over plain HTTP on a host other than localhost. */
    private boolean cookieSecure = true;
    private String cookieSameSite = "Strict";

    public long getRefreshTtlDays() {
        return refreshTtlDays;
    }

    public void setRefreshTtlDays(long refreshTtlDays) {
        this.refreshTtlDays = refreshTtlDays;
    }

    public long getRotationGraceSeconds() {
        return rotationGraceSeconds;
    }

    public void setRotationGraceSeconds(long rotationGraceSeconds) {
        this.rotationGraceSeconds = rotationGraceSeconds;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite;
    }
}
