package com.kanbancord_api.task;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/** A role assigned to a task. Role and user ids are strings, as Discord snowflakes exceed JavaScript's safe integers. */
public record TaskRoleAssignmentResponse(
        Long id,
        Long taskId,
        @JsonSerialize(using = ToStringSerializer.class) Long roleId,
        @JsonSerialize(using = ToStringSerializer.class) Long assignedBy,
        LocalDateTime assignedAt) {

    public static TaskRoleAssignmentResponse from(TaskRoleAssignment assignment) {
        return new TaskRoleAssignmentResponse(assignment.getId(), assignment.getTaskId(), assignment.getRoleId(),
                assignment.getAssignedBy(), assignment.getAssignedAt());
    }
}
