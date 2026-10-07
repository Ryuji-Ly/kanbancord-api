package com.kanbancord_api.sync;

import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

public record InternalMemberRoleSyncRequest(
        @NotNull(message = "Role IDs list is required") List<Long> roleIds) {

    public InternalMemberRoleSyncRequest {
        if (roleIds == null) {
            roleIds = new ArrayList<>();
        }
    }
}
