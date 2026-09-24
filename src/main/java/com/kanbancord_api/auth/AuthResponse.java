package com.kanbancord_api.auth;

import com.kanbancord_api.user.UserResponse;

public class AuthResponse {

    private String tokenType;
    private String accessToken;
    private long expiresIn;
    private UserResponse user;
    private String discordAccessToken;

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserResponse getUser() {
        return user;
    }

    public void setUser(UserResponse user) {
        this.user = user;
    }

    public String getDiscordAccessToken() {
        return discordAccessToken;
    }

    public void setDiscordAccessToken(String discordAccessToken) {
        this.discordAccessToken = discordAccessToken;
    }
}
