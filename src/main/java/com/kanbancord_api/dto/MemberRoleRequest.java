package com.kanbancord_api.dto;

import jakarta.validation.constraints.NotNull;

public class MemberRoleRequest {

    @NotNull(message = "Server member ID is required")
    private Long serverMemberId;

    @NotNull(message = "Role ID is required")
    private Long roleId;

    public Long getServerMemberId() {
        return serverMemberId;
    }

    public void setServerMemberId(Long serverMemberId) {
        this.serverMemberId = serverMemberId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }
}
