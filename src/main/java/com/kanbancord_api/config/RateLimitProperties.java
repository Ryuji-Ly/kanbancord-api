package com.kanbancord_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Request rate limits. Signed-in users are limited per user (reads and writes separately);
 * sign-in requests are limited per client IP.
 *
 * <p>Limits are deliberately generous: the web client still sends one request per task when a task is
 * dragged, and loads board capabilities one board at a time.
 */
@Component
@ConfigurationProperties(prefix = "kanbancord.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Limit auth = new Limit(20, 10);
    /** Refreshing and signing out: every page load refreshes once, so this is looser than sign-in. */
    private Limit session = new Limit(60, 30);
    private Limit read = new Limit(600, 600);
    private Limit write = new Limit(300, 300);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Limit getAuth() {
        return auth;
    }

    public void setAuth(Limit auth) {
        this.auth = auth;
    }

    public Limit getSession() {
        return session;
    }

    public void setSession(Limit session) {
        this.session = session;
    }

    public Limit getRead() {
        return read;
    }

    public void setRead(Limit read) {
        this.read = read;
    }

    public Limit getWrite() {
        return write;
    }

    public void setWrite(Limit write) {
        this.write = write;
    }

    /** A token bucket: up to {@code capacity} requests at once, refilled at {@code perMinute}. */
    public static class Limit {

        private int capacity;
        private int perMinute;

        public Limit() {
        }

        public Limit(int capacity, int perMinute) {
            this.capacity = capacity;
            this.perMinute = perMinute;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }
    }
}
