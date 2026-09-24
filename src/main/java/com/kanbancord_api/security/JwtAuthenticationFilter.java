package com.kanbancord_api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.kanbancord_api.session.UserSessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final UserSessionService userSessionService;

    public JwtAuthenticationFilter(
            ObjectProvider<JwtTokenService> jwtTokenServiceProvider,
            ObjectProvider<UserSessionService> userSessionServiceProvider) {
        this.jwtTokenService = jwtTokenServiceProvider.getIfAvailable();
        this.userSessionService = userSessionServiceProvider.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (jwtTokenService != null && userSessionService != null && header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            jwtTokenService.validate(token)
                    // A signed-out session's tokens stop working at once, not when they expire.
                    .filter(claims -> userSessionService.isActive(claims.sessionId(), claims.userId()))
                    .ifPresent(claims -> {
                        if (SecurityContextHolder.getContext().getAuthentication() == null) {
                            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                    claims.userId(),
                                    null,
                                    List.of());
                            auth.setDetails(new AuthenticatedSession(claims.sessionId()));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    });
        }

        filterChain.doFilter(request, response);
    }
}
