package com.kanbancord_api.config;

import com.kanbancord_api.security.BotActingUserFilter;
import com.kanbancord_api.security.JwtAuthenticationFilter;
import com.kanbancord_api.security.RateLimitFilter;
import com.kanbancord_api.security.TokenBucketRateLimiter;
import com.kanbancord_api.sync.InternalSyncProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
public class SecurityConfig {

        @Bean
        public SecurityFilterChain securityFilterChain(
                HttpSecurity http,
                JwtAuthenticationFilter jwtAuthenticationFilter,
                RateLimitProperties rateLimitProperties,
                InternalSyncProperties internalSyncProperties)
                throws Exception {

                http
                    .cors(cors -> {})
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(
                                    "/api/auth/**",
                                    "/api/internal/sync/**",
                                    "/api/internal/notifications/**",
                                    "/actuator/health",
                                    "/ws/**")
                            .permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .httpBasic(httpBasic -> httpBasic.disable())
                    .formLogin(formLogin -> formLogin.disable())
                    .addFilterBefore(
                            jwtAuthenticationFilter,
                            UsernamePasswordAuthenticationFilter.class)
                    // Slash commands: the bot acting as the user who ran one.
                    .addFilterBefore(
                            new BotActingUserFilter(internalSyncProperties),
                            UsernamePasswordAuthenticationFilter.class)
                    // After the JWT filter, so signed-in callers are limited per user.
                    .addFilterAfter(
                            new RateLimitFilter(rateLimitProperties, new TokenBucketRateLimiter()),
                            UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }


        @Bean
        public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
                CorsConfiguration configuration = new CorsConfiguration();

                configuration.setAllowedOrigins(List.copyOf(corsProperties.getAllowedOrigins()));

                configuration.setAllowedMethods(List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                ));

                configuration.setAllowedHeaders(List.of("*"));

                configuration.setAllowCredentials(true);

                UrlBasedCorsConfigurationSource source =
                        new UrlBasedCorsConfigurationSource();

                source.registerCorsConfiguration("/**", configuration);

                return source;
        }
}