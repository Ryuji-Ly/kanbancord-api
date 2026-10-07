package com.kanbancord_api.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A whole server as the bot sees it: the server, its owner, its roles and its members. Lists left out are empty. */
public record InternalBootstrapRequest(
        @NotBlank(message = "Server name is required")
        @Size(max = 255, message = "Server name must not exceed 255 characters")
        String name,

        @Size(max = 1024, message = "Icon URL must not exceed 1024 characters")
        String iconUrl,

        @NotNull(message = "Owner ID is required")
        Long ownerId,

        @NotBlank(message = "Owner username is required")
        @Size(max = 255, message = "Owner username must not exceed 255 characters")
        String ownerUsername,

        @Size(max = 100, message = "Owner global name must not exceed 100 characters")
        String ownerGlobalName,

        @Size(max = 1024, message = "Owner avatar URL must not exceed 1024 characters")
        String ownerAvatarUrl,

        @Valid
        @NotNull(message = "Roles list is required")
        List<RoleEntry> roles,

        @Valid
        @NotNull(message = "Members list is required")
        List<MemberEntry> members) {

    public InternalBootstrapRequest {
        if (roles == null) {
            roles = new ArrayList<>();
        }
        if (members == null) {
            members = new ArrayList<>();
        }
    }

    public record RoleEntry(
            @NotNull(message = "Role ID is required")
            Long roleId,

            @NotBlank(message = "Role name is required")
            @Size(max = 255, message = "Role name must not exceed 255 characters")
            String name,

            Integer color,

            Integer position,

            @NotNull(message = "Discord permissions bitset is required")
            Long discordPermissions) {
    }

    public record MemberEntry(
            @NotNull(message = "User ID is required")
            Long userId,

            @NotBlank(message = "Username is required")
            @Size(max = 255, message = "Username must not exceed 255 characters")
            String username,

            @Size(max = 100, message = "Global name must not exceed 100 characters")
            String globalName,

            @Size(max = 1024, message = "Avatar URL must not exceed 1024 characters")
            String avatarUrl,

            @Size(max = 255, message = "Nickname must not exceed 255 characters")
            String nickname,

            LocalDateTime joinedAt,

            List<Long> roleIds) {

        public MemberEntry {
            if (roleIds == null) {
                roleIds = new ArrayList<>();
            }
        }
    }
}
