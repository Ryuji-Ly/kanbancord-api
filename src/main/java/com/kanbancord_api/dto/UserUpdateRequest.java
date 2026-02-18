package com.kanbancord_api.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;

public class UserUpdateRequest {

    @Size(max = 100)
    private String globalName;

    @Size(max = 255)
    private String avatarUrl;

    private Map<String, Object> preferences;

    // Getters and Setters
    public String getGlobalName() {
        return globalName;
    }

    public void setGlobalName(String globalName) {
        this.globalName = globalName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Map<String, Object> getPreferences() {
        return preferences;
    }

    public void setPreferences(Map<String, Object> preferences) {
        this.preferences = preferences;
    }
}
