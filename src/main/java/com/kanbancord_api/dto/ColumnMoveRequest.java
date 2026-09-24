package com.kanbancord_api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Moves a column to {@code index} (0-based) among the board's columns in order. */
public class ColumnMoveRequest {

    @NotNull(message = "Index is required")
    @Min(value = 0, message = "Index must not be negative")
    private Integer index;

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }
}
