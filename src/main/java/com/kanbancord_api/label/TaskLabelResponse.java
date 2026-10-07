package com.kanbancord_api.label;

import java.time.LocalDateTime;

public record TaskLabelResponse(
        Long id,
        Long taskId,
        Long labelId,
        LocalDateTime addedAt) {

    public static TaskLabelResponse from(TaskLabel taskLabel) {
        return new TaskLabelResponse(
                taskLabel.getId(),
                taskLabel.getTask().getTaskId(),
                taskLabel.getLabel().getLabelId(),
                taskLabel.getAddedAt());
    }
}
