package com.kanbancord_api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Moves a task to {@code index} (0-based, among the column's tasks in order) of column {@code columnId}. */
public class TaskMoveRequest {

    @NotNull(message = "Column ID is required")
    private Long columnId;

    @NotNull(message = "Index is required")
    @Min(value = 0, message = "Index must not be negative")
    private Integer index;

    public Long getColumnId() {
        return columnId;
    }

    public void setColumnId(Long columnId) {
        this.columnId = columnId;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }
}
