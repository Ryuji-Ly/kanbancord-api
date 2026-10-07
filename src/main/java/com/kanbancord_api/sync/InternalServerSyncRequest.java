package com.kanbancord_api.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InternalServerSyncRequest(
        @NotBlank(message = "Server name is required") @Size(max = 255, message = "Server name must not exceed 255 characters") String name,
        @Size(max = 1024, message = "Icon URL must not exceed 1024 characters") String iconUrl,
        @NotNull(message = "Owner ID is required") Long ownerId,
        @NotBlank(message = "Owner username is required") @Size(max = 255, message = "Owner username must not exceed 255 characters") String ownerUsername,
        @Size(max = 100, message = "Owner global name must not exceed 100 characters") String ownerGlobalName,
        @Size(max = 1024, message = "Owner avatar URL must not exceed 1024 characters") String ownerAvatarUrl) {
}
