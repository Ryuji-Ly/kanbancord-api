package com.kanbancord_api.event;

/**
 * Everything that can happen to server data. Each type names the entity it concerns and whether it
 * is announced server-wide (the board list and permission screens listen there) as well as on its board.
 *
 * <p>Changes synced from Discord by the bot (roles, members, the server itself) are announced so open
 * pages refresh, but are not written to the audit log: the audit log records what people did in
 * KanbanCord, and Discord keeps its own.
 */
public enum EventType {

    BOARD_CREATED(EntityType.BOARD, true),
    BOARD_UPDATED(EntityType.BOARD, true),
    BOARD_ARCHIVED(EntityType.BOARD, true),
    BOARD_RESTORED(EntityType.BOARD, true),
    BOARD_DELETED(EntityType.BOARD, true),

    COLUMN_CREATED(EntityType.BOARD_COLUMN, false),
    COLUMN_UPDATED(EntityType.BOARD_COLUMN, false),
    COLUMN_MOVED(EntityType.BOARD_COLUMN, false),
    COLUMN_DELETED(EntityType.BOARD_COLUMN, false),

    TASK_CREATED(EntityType.TASK, false),
    TASK_UPDATED(EntityType.TASK, false),
    TASK_MOVED(EntityType.TASK, false),
    TASK_DELETED(EntityType.TASK, false),

    TASK_ASSIGNMENT_CREATED(EntityType.TASK_ASSIGNMENT, false),
    TASK_ASSIGNMENT_DELETED(EntityType.TASK_ASSIGNMENT, false),
    TASK_ROLE_ASSIGNED(EntityType.TASK_ASSIGNMENT, false),
    TASK_ROLE_UNASSIGNED(EntityType.TASK_ASSIGNMENT, false),

    TASK_COMMENT_CREATED(EntityType.TASK_COMMENT, false),
    TASK_COMMENT_UPDATED(EntityType.TASK_COMMENT, false),
    TASK_COMMENT_DELETED(EntityType.TASK_COMMENT, false),

    LABEL_CREATED(EntityType.LABEL, false),
    LABEL_UPDATED(EntityType.LABEL, false),
    LABEL_DELETED(EntityType.LABEL, false),

    TASK_LABEL_ADDED(EntityType.TASK_LABEL, false),
    TASK_LABEL_REMOVED(EntityType.TASK_LABEL, false),

    PRIORITY_CREATED(EntityType.PRIORITY, false),
    PRIORITY_UPDATED(EntityType.PRIORITY, false),
    PRIORITY_MOVED(EntityType.PRIORITY, false),
    PRIORITY_DELETED(EntityType.PRIORITY, false),

    PERMISSION_CREATED(EntityType.PERMISSION, true),
    PERMISSION_UPDATED(EntityType.PERMISSION, true),
    PERMISSION_DELETED(EntityType.PERMISSION, true),

    SERVER_FEATURES_UPDATED(EntityType.SETTINGS, true),
    /** The server's Discord notification channels and feeds. */
    NOTIFICATIONS_UPDATED(EntityType.SETTINGS, true),
    /** Only the board's own page needs to know; the server's board list does not change. */
    BOARD_FEATURES_UPDATED(EntityType.SETTINGS, false),

    SERVER_SYNCED(EntityType.SERVER, true),
    ROLE_SYNCED(EntityType.ROLE, true),
    ROLE_REMOVED(EntityType.ROLE, true),
    MEMBER_SYNCED(EntityType.MEMBER, true),
    MEMBER_REMOVED(EntityType.MEMBER, true);

    public enum EntityType {
        BOARD, BOARD_COLUMN, TASK, TASK_ASSIGNMENT, TASK_COMMENT, LABEL, TASK_LABEL, PRIORITY, PERMISSION, SETTINGS,
        SERVER, ROLE, MEMBER
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

    /** Whether the change can alter who may see or do what, so access must be re-evaluated after it. */
    public boolean affectsAccess() {
        return switch (entityType) {
            case PERMISSION, SERVER, ROLE, MEMBER -> true;
            // Custom and open permissions change which rules apply.
            default -> this == SERVER_FEATURES_UPDATED;
        };
    }

    /** Whether the change came from Discord through the bot rather than from a user in KanbanCord. */
    public boolean fromDiscordSync() {
        return switch (entityType) {
            case SERVER, ROLE, MEMBER -> true;
            default -> false;
        };
    }
}
