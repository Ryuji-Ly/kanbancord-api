package com.kanbancord_api.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class InternalMemberSyncRequest {

    @NotBlank(message = "Username is required")
    @Size(max = 255, message = "Username must not exceed 255 characters")
    private String username;

    @Size(max = 100, message = "Global name must not exceed 100 characters")
    private String globalName;

    @Size(max = 1024, message = "Avatar URL must not exceed 1024 characters")
    private String avatarUrl;

    @Size(max = 255, message = "Nickname must not exceed 255 characters")
    private String nickname;

    private LocalDateTime joinedAt;

    private List<Long> roleIds = new ArrayList<>();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

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

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
