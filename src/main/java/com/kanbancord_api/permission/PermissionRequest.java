package com.kanbancord_api.permission;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PermissionRequest(
        @NotBlank(message = "Scope type is required") @Pattern(regexp = "^(SERVER|BOARD)$", message = "Scope type must be SERVER or BOARD") String scopeType,
        @NotNull(message = "Scope ID is required") Long scopeId,
        @NotBlank(message = "Subject type is required") @Pattern(regexp = "^(USER|ROLE|DISCORD_PERMISSION)$", message = "Subject type must be USER, ROLE, or DISCORD_PERMISSION") String subjectType,
        @NotNull(message = "Subject ID is required") Long subjectId,
        @NotNull(message = "Kanban permission ID is required") Integer kanbanPermissionId,
        @NotBlank(message = "State is required") @Pattern(regexp = "^(ALLOW|DENY)$", message = "State must be ALLOW or DENY") String state,
        @NotNull(message = "Priority is required") @Min(value = 0, message = "Priority must be at least 0") @Max(value = 1000, message = "Priority must be at most 1000") Integer priority,
        Boolean isImmutable) {
}
