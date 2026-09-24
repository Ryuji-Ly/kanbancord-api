package com.kanbancord_api.security;

import com.kanbancord_api.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private final RateLimitProperties properties = limits();
    private final RateLimitFilter filter = new RateLimitFilter(properties, new TokenBucketRateLimiter());

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void signedInUsers_areLimitedPerUser_readsAndWritesSeparately() throws Exception {
        signIn(1L);
        assertEquals(200, send("POST", "/api/servers/1/boards").getStatus());
        MockHttpServletResponse limited = send("POST", "/api/servers/1/boards");
        assertEquals(429, limited.getStatus());
        assertNotNull(limited.getHeader("Retry-After"));
        assertTrue(limited.getContentAsString().contains("\"status\":429"));

        assertEquals(200, send("GET", "/api/servers/1/boards").getStatus(), "reads have their own bucket");

        signIn(2L);
        assertEquals(200, send("POST", "/api/servers/1/boards").getStatus(), "each user has their own bucket");
    }

    @Test
    void signIn_isLimitedPerClientIp() throws Exception {
        assertEquals(200, send("POST", "/api/auth/discord/exchange", "203.0.113.1").getStatus());
        assertEquals(429, send("POST", "/api/auth/discord/exchange", "203.0.113.1").getStatus());
        assertEquals(200, send("POST", "/api/auth/discord/exchange", "203.0.113.2").getStatus());
    }

    @Test
    void internalSync_preflights_andOtherUnauthenticatedRequests_areNotLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send("PUT", "/api/internal/sync/servers/1").getStatus());
            assertEquals(200, send("OPTIONS", "/api/servers/1/boards").getStatus());
            assertEquals(200, send("GET", "/api/servers/1/boards").getStatus());
        }
    }

    @Test
    void canBeDisabled() throws Exception {
        properties.setEnabled(false);
        signIn(1L);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send("POST", "/api/servers/1/boards").getStatus());
        }
    }

    private MockHttpServletResponse send(String method, String path) throws Exception {
        return send(method, path, "198.51.100.7");
    }

    private MockHttpServletResponse send(String method, String path, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static void signIn(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static RateLimitProperties limits() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setAuth(new RateLimitProperties.Limit(1, 1));
        properties.setRead(new RateLimitProperties.Limit(1, 1));
        properties.setWrite(new RateLimitProperties.Limit(1, 1));
        return properties;
    }
}
