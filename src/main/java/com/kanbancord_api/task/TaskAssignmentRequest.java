package com.kanbancord_api.task;

import jakarta.validation.constraints.NotNull;

/**
 * @param assignedBy ignored: the assigner is always the authenticated user; kept for client compatibility
 */
public record TaskAssignmentRequest(
        @NotNull(message = "Task ID is required") Long taskId,
        @NotNull(message = "User ID is required") Long userId,
        Long assignedBy) {
}
