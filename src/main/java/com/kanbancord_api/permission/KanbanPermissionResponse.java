package com.kanbancord_api.permission;

public record KanbanPermissionResponse(
        Integer permissionId,
        String key,
        String name,
        String description,
        String category) {
}
