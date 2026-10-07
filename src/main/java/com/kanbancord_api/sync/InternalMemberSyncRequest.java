package com.kanbancord_api.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record InternalMemberSyncRequest(
        @NotBlank(message = "Username is required") @Size(max = 255, message = "Username must not exceed 255 characters") String username,
        @Size(max = 100, message = "Global name must not exceed 100 characters") String globalName,
        @Size(max = 1024, message = "Avatar URL must not exceed 1024 characters") String avatarUrl,
        @Size(max = 255, message = "Nickname must not exceed 255 characters") String nickname,
        LocalDateTime joinedAt,
        List<Long> roleIds) {

    public InternalMemberSyncRequest {
        if (roleIds == null) {
            roleIds = new ArrayList<>();
        }
    }
}
