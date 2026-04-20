package com.kanbancord_api.controller;

import com.kanbancord_api.dto.AuthResponse;
import com.kanbancord_api.dto.DiscordLoginRequest;
import com.kanbancord_api.dto.DiscordOAuthCodeExchangeRequest;
import com.kanbancord_api.dto.UserResponse;
import com.kanbancord_api.model.User;
import com.kanbancord_api.security.JwtTokenService;
import com.kanbancord_api.service.DiscordIdentityService;
import com.kanbancord_api.service.DiscordOAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final DiscordIdentityService discordIdentityService;
    private final DiscordOAuthService discordOAuthService;
    private final JwtTokenService jwtTokenService;

    public AuthController(
            DiscordIdentityService discordIdentityService,
            DiscordOAuthService discordOAuthService,
            JwtTokenService jwtTokenService) {
        this.discordIdentityService = discordIdentityService;
        this.discordOAuthService = discordOAuthService;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/discord/login")
    public ResponseEntity<AuthResponse> discordLogin(@Valid @RequestBody DiscordLoginRequest request) {
        User user = discordIdentityService.authenticateAndSyncUser(request.getAccessToken());
        return ResponseEntity.ok(toAuthResponse(user));
    }

    @PostMapping("/discord/exchange")
    public ResponseEntity<AuthResponse> exchangeCodeAndLogin(
            @Valid @RequestBody DiscordOAuthCodeExchangeRequest request) {
        String discordAccessToken = discordOAuthService.exchangeCodeForAccessToken(request.getCode(),
                request.getRedirectUri());
        User user = discordIdentityService.authenticateAndSyncUser(discordAccessToken);
        AuthResponse response = toAuthResponse(user);
        response.setDiscordAccessToken(discordAccessToken);
        return ResponseEntity.ok(response);
    }

    private AuthResponse toAuthResponse(User user) {
        AuthResponse response = new AuthResponse();
        response.setTokenType("Bearer");
        response.setAccessToken(jwtTokenService.issueToken(user.getUserId()));
        response.setExpiresIn(jwtTokenService.getExpirationSeconds());
        response.setUser(toUserResponse(user));
        return response;
    }

    private UserResponse toUserResponse(User user) {
        UserResponse response = new UserResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setGlobalName(user.getGlobalName());
        response.setAvatarUrl(user.getAvatarUrl());
        response.setPreferences(user.getPreferences());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}
