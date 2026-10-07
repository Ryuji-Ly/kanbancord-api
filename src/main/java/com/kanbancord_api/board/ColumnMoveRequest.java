package com.kanbancord_api.board;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Moves a column to {@code index} (0-based) among the board's columns in order. */
public record ColumnMoveRequest(
        @NotNull(message = "Index is required") @Min(value = 0, message = "Index must not be negative") Integer index) {
}
