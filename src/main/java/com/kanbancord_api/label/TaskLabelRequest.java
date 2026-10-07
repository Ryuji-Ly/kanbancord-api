package com.kanbancord_api.label;

import jakarta.validation.constraints.NotNull;

public record TaskLabelRequest(
        @NotNull(message = "Task ID is required") Long taskId,
        @NotNull(message = "Label ID is required") Long labelId) {
}
