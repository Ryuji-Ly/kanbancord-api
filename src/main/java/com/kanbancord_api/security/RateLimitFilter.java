package com.kanbancord_api.security;

import com.kanbancord_api.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Rejects requests over the configured rate with 429.
 *
 * <p>Runs after {@link JwtAuthenticationFilter}: signed-in callers are limited per user, reads and
 * writes separately; unauthenticated sign-in requests are limited per client IP. Everything else that
 * is unauthenticated is left to fail authentication, and the bot's internal sync is not limited.
 *
 * <p>Not a bean on purpose: as a bean Spring Boot would also register it as a servlet filter and
 * every request would be counted twice.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final RateLimitProperties properties;
    private final TokenBucketRateLimiter limiter;

    public RateLimitFilter(RateLimitProperties properties, TokenBucketRateLimiter limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !properties.isEnabled()
                || !path.startsWith("/api/")
                || path.startsWith("/api/internal/sync/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        long retryAfterSeconds = acquire(request);
        if (retryAfterSeconds > 0) {
            reject(response, retryAfterSeconds);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private long acquire(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            boolean read = READ_METHODS.contains(request.getMethod().toUpperCase());
            return read
                    ? limiter.tryAcquire("user:" + userId + ":read", properties.getRead())
                    : limiter.tryAcquire("user:" + userId + ":write", properties.getWrite());
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/auth/")) {
            // The client IP; behind Cloudflare and nginx, Tomcat resolves it from CF-Connecting-IP.
            return limiter.tryAcquire("ip:" + request.getRemoteAddr() + ":auth", properties.getAuth());
        }
        return 0;
    }

    private static void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":429,\"message\":\"Too many requests, try again in "
                + retryAfterSeconds + "s\",\"timestamp\":\"" + LocalDateTime.now() + "\"}");
    }
}
