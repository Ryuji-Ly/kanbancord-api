package com.kanbancord_api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A user's Discord OAuth tokens, encrypted by {@link com.kanbancord_api.security.TokenCipher}. */
@Entity
@Table(name = "discord_credentials")
public class DiscordCredential {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "access_token", nullable = false)
    private byte[] accessToken;

    @Column(name = "refresh_token")
    private byte[] refreshToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "scope")
    private String scope;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public byte[] getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(byte[] accessToken) {
        this.accessToken = accessToken;
    }

    public byte[] getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(byte[] refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
