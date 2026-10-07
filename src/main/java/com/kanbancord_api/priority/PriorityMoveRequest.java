package com.kanbancord_api.priority;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Moves a priority level to {@code index} (0-based, most urgent first) among the board's levels. */
public record PriorityMoveRequest(
        @NotNull(message = "Index is required") @Min(value = 0, message = "Index must not be negative") Integer index) {
}
