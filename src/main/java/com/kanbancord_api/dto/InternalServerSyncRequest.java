package com.kanbancord_api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class InternalServerSyncRequest {

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
}
