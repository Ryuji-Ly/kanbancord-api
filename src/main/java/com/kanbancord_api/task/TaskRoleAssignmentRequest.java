package com.kanbancord_api.task;

import jakarta.validation.constraints.NotNull;

public record TaskRoleAssignmentRequest(
        @NotNull(message = "Role ID is required") Long roleId) {
}
