package com.kanbancord_api.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * @param priorityId one of the board's priority levels, or null for none; replaces the task's priority on update
 */
public record TaskRequest(
        @NotBlank(message = "Title is required") @Size(max = 200, message = "Title must not exceed 200 characters") String title,
        String description,
        @NotNull(message = "Board ID is required") Long boardId,
        @NotNull(message = "Column ID is required") Long columnId,
        BigDecimal position,
        Long priorityId,
        LocalDateTime dueDate,
        Map<String, Object> metadata,
        Long createdBy) {

    /** The same request, with this priority and due date. */
    public TaskRequest withPriorityAndDue(Long priorityId, LocalDateTime dueDate) {
        return new TaskRequest(title, description, boardId, columnId, position, priorityId, dueDate, metadata, createdBy);
    }
}
