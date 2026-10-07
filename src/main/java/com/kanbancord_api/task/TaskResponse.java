package com.kanbancord_api.task;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record TaskResponse(
        Long taskId,
        Long boardId,
        Long columnId,
        String title,
        String description,
        BigDecimal position,
        Long priorityId,
        LocalDateTime dueDate,
        Boolean isArchived,
        Map<String, Object> metadata,
        @JsonSerialize(using = ToStringSerializer.class) Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getTaskId(),
                task.getBoard().getBoardId(),
                task.getColumn().getColumnId(),
                task.getTitle(),
                task.getDescription(),
                task.getPosition(),
                task.getPriorityId(),
                task.getDueDate(),
                task.getIsArchived(),
                task.getMetadata(),
                task.getCreatedBy() == null ? null : task.getCreatedBy().getUserId(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt());
    }
}
