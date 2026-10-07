package com.kanbancord_api.task;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record TaskAssignmentResponse(
        Long id,
        Long taskId,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        @JsonSerialize(using = ToStringSerializer.class) Long assignedBy,
        LocalDateTime assignedAt) {

    public static TaskAssignmentResponse from(TaskAssignment assignment) {
        return new TaskAssignmentResponse(
                assignment.getId(),
                assignment.getTask().getTaskId(),
                assignment.getUser().getUserId(),
                assignment.getAssignedBy().getUserId(),
                assignment.getAssignedAt());
    }
}
