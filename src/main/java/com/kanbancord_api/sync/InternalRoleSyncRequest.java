package com.kanbancord_api.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InternalRoleSyncRequest(
        @NotBlank(message = "Role name is required") @Size(max = 255, message = "Role name must not exceed 255 characters") String name,
        Integer color,
        Integer position,
        @NotNull(message = "Discord permissions bitset is required") Long discordPermissions) {
}
