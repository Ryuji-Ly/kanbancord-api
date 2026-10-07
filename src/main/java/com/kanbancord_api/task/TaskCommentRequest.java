package com.kanbancord_api.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskCommentRequest(
        @NotNull(message = "Task ID is required") Long taskId,
        Long userId,
        @NotBlank(message = "Content is required") @Size(max = 2000, message = "Content must not exceed 2000 characters") String content,
        Long replyToId) {
}
