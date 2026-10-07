package com.kanbancord_api.task;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Moves a task to {@code index} (0-based, among the column's tasks in order) of column {@code columnId}. */
public record TaskMoveRequest(
        @NotNull(message = "Column ID is required") Long columnId,
        @NotNull(message = "Index is required") @Min(value = 0, message = "Index must not be negative") Integer index) {
}
