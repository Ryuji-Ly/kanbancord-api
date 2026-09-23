package com.kanbancord_api.api;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

/**
 * Authenticates a MockMvc request the same way {@code JwtAuthenticationFilter} does (principal = user
 * id). The API slices run with security filters disabled, so the context is set directly on the
 * thread; Spring Security's test listener clears it after each test.
 */
final class ApiTestAuth {

    private ApiTestAuth() {
    }

    static RequestPostProcessor asUser(long userId) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            TestSecurityContextHolder.setContext(context);
            return request;
        };
    }
}
