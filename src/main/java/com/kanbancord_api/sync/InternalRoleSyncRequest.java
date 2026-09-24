package com.kanbancord_api.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class InternalRoleSyncRequest {

    @NotBlank(message = "Role name is required")
    @Size(max = 255, message = "Role name must not exceed 255 characters")
    private String name;

    private Integer color;

    private Integer position;

    @NotNull(message = "Discord permissions bitset is required")
    private Long discordPermissions;

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
