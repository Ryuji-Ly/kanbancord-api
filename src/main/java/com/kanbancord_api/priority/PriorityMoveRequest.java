package com.kanbancord_api.priority;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Moves a priority level to {@code index} (0-based, most urgent first) among the board's levels. */
public class PriorityMoveRequest {

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
