package com.kanbancord_api.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class InternalBootstrapRequest {

    @NotBlank(message = "Server name is required")
    @Size(max = 255, message = "Server name must not exceed 255 characters")
    private String name;

    @Size(max = 1024, message = "Icon URL must not exceed 1024 characters")
    private String iconUrl;

    @NotNull(message = "Owner ID is required")
    private Long ownerId;

    @NotBlank(message = "Owner username is required")
    @Size(max = 255, message = "Owner username must not exceed 255 characters")
    private String ownerUsername;

    @Size(max = 100, message = "Owner global name must not exceed 100 characters")
    private String ownerGlobalName;

    @Size(max = 1024, message = "Owner avatar URL must not exceed 1024 characters")
    private String ownerAvatarUrl;

    @Valid
    @NotNull(message = "Roles list is required")
    private List<RoleEntry> roles = new ArrayList<>();

    @Valid
    @NotNull(message = "Members list is required")
    private List<MemberEntry> members = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public String getOwnerGlobalName() {
        return ownerGlobalName;
    }

    public void setOwnerGlobalName(String ownerGlobalName) {
        this.ownerGlobalName = ownerGlobalName;
    }

    public String getOwnerAvatarUrl() {
        return ownerAvatarUrl;
    }

    public void setOwnerAvatarUrl(String ownerAvatarUrl) {
        this.ownerAvatarUrl = ownerAvatarUrl;
    }

    public List<RoleEntry> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleEntry> roles) {
        this.roles = roles;
    }

    public List<MemberEntry> getMembers() {
        return members;
    }

    public void setMembers(List<MemberEntry> members) {
        this.members = members;
    }

    public static class RoleEntry {

        @NotNull(message = "Role ID is required")
        private Long roleId;

        @NotBlank(message = "Role name is required")
        @Size(max = 255, message = "Role name must not exceed 255 characters")
        private String name;

        private Integer color;

        private Integer position;

        @NotNull(message = "Discord permissions bitset is required")
        private Long discordPermissions;

        public Long getRoleId() {
            return roleId;
        }

        public void setRoleId(Long roleId) {
            this.roleId = roleId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getColor() {
            return color;
        }

        public void setColor(Integer color) {
            this.color = color;
        }

        public Integer getPosition() {
            return position;
        }

        public void setPosition(Integer position) {
            this.position = position;
        }

        public Long getDiscordPermissions() {
            return discordPermissions;
        }

        public void setDiscordPermissions(Long discordPermissions) {
            this.discordPermissions = discordPermissions;
        }
    }

    public static class MemberEntry {

        @NotNull(message = "User ID is required")
        private Long userId;

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

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

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
}
