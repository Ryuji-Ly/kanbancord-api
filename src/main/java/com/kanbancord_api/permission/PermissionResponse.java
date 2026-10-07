package com.kanbancord_api.permission;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record PermissionResponse(
        Long id,
        String scopeType,
        @JsonSerialize(using = ToStringSerializer.class) Long scopeId,
        String subjectType,
        @JsonSerialize(using = ToStringSerializer.class) Long subjectId,
        Integer kanbanPermissionId,
        String kanbanPermissionKey,
        String state,
        Integer priority,
        Boolean isImmutable,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PermissionResponse from(Permission permission) {
        KanbanPermission kanbanPermission = permission.getKanbanPermission();
        return new PermissionResponse(
                permission.getId(),
                permission.getScopeType(),
                permission.getScopeId(),
                permission.getSubjectType(),
                permission.getSubjectId(),
                kanbanPermission == null ? null : kanbanPermission.getPermissionId(),
                kanbanPermission == null ? null : kanbanPermission.getKey(),
                permission.getState(),
                permission.getPriority(),
                permission.getIsImmutable(),
                permission.getCreatedAt(),
                permission.getUpdatedAt());
    }
}
