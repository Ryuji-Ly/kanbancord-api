package com.kanbancord_api.dto;

import jakarta.validation.constraints.NotNull;

public class TaskLabelRequest {

    @NotNull(message = "Task ID is required")
    private Long taskId;

    @NotNull(message = "Label ID is required")
    private Long labelId;

    // Getters and Setters
    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }
}
