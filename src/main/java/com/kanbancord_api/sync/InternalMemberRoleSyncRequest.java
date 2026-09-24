package com.kanbancord_api.sync;

import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

public class InternalMemberRoleSyncRequest {

    @NotNull(message = "Role IDs list is required")
    private List<Long> roleIds = new ArrayList<>();

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
