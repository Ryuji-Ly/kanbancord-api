package com.kanbancord_api.event;

/**
 * Everything that can happen to server data. Each type names the entity it concerns and whether it
 * is announced server-wide (the board list and permission screens listen there) as well as on its board.
 */
public enum EventType {

    BOARD_CREATED(EntityType.BOARD, true),
    BOARD_UPDATED(EntityType.BOARD, true),
    BOARD_ARCHIVED(EntityType.BOARD, true),
    BOARD_RESTORED(EntityType.BOARD, true),
    BOARD_DELETED(EntityType.BOARD, true),

    COLUMN_CREATED(EntityType.BOARD_COLUMN, false),
    COLUMN_UPDATED(EntityType.BOARD_COLUMN, false),
    COLUMN_DELETED(EntityType.BOARD_COLUMN, false),

    TASK_CREATED(EntityType.TASK, false),
    TASK_UPDATED(EntityType.TASK, false),
    TASK_DELETED(EntityType.TASK, false),

    TASK_ASSIGNMENT_CREATED(EntityType.TASK_ASSIGNMENT, false),
    TASK_ASSIGNMENT_DELETED(EntityType.TASK_ASSIGNMENT, false),

    TASK_COMMENT_CREATED(EntityType.TASK_COMMENT, false),
    TASK_COMMENT_UPDATED(EntityType.TASK_COMMENT, false),
    TASK_COMMENT_DELETED(EntityType.TASK_COMMENT, false),

    PERMISSION_CREATED(EntityType.PERMISSION, true),
    PERMISSION_UPDATED(EntityType.PERMISSION, true),
    PERMISSION_DELETED(EntityType.PERMISSION, true);

    public enum EntityType {
        BOARD, BOARD_COLUMN, TASK, TASK_ASSIGNMENT, TASK_COMMENT, PERMISSION
    }

    private final EntityType entityType;
    private final boolean serverWide;

    EventType(EntityType entityType, boolean serverWide) {
        this.entityType = entityType;
        this.serverWide = serverWide;
    }

    public EntityType entityType() {
        return entityType;
    }

    public boolean serverWide() {
        return serverWide;
    }
}
