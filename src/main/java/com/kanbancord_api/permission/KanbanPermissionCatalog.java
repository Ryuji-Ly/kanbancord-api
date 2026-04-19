package com.kanbancord_api.permission;

import java.util.Arrays;
import java.util.Optional;

public enum KanbanPermissionCatalog {

    VIEW_SERVER("VIEW_SERVER", "View Server", "View server overview and metadata", "SERVER", true, false),
    EDIT_SERVER_DETAILS("EDIT_SERVER_DETAILS", "Edit Server Details", "Edit server-level details and settings",
            "SERVER", true, false),
    EDIT_SERVER_PERMISSIONS("EDIT_SERVER_PERMISSIONS", "Edit Server Permissions",
            "Manage permission rules and defaults", "SERVER", true, false),
    MANAGE_SERVER_ROLES("MANAGE_SERVER_ROLES", "Manage Server Roles", "Manage role sync and role assignments", "SERVER",
            true, false),
    MANAGE_SERVER_MEMBERS("MANAGE_SERVER_MEMBERS", "Manage Server Members", "Manage server membership and access",
            "SERVER", true, false),
    VIEW_AUDIT_LOG("VIEW_AUDIT_LOG", "View Audit Log", "Read audit events", "SERVER", true, false),

    CREATE_BOARD("CREATE_BOARD", "Create Board", "Create new boards", "BOARD", true, false),
    VIEW_BOARD("VIEW_BOARD", "View Board", "View board content", "BOARD", true, true),
    EDIT_BOARD_DETAILS("EDIT_BOARD_DETAILS", "Edit Board Details", "Edit board name, description, and state", "BOARD",
            true, true),
    EDIT_BOARD_PERMISSIONS("EDIT_BOARD_PERMISSIONS", "Edit Board Permissions",
            "Manage board-scoped permission overrides", "BOARD", true, true),
    ARCHIVE_BOARD("ARCHIVE_BOARD", "Archive Board", "Archive and restore boards", "BOARD", true, true),
    DELETE_BOARD("DELETE_BOARD", "Delete Board", "Delete boards", "BOARD", true, true),

    CREATE_COLUMN("CREATE_COLUMN", "Create Columns", "Create board columns", "COLUMN", true, true),
    EDIT_COLUMN("EDIT_COLUMN", "Edit Columns", "Edit column properties", "COLUMN", true, true),
    DELETE_COLUMN("DELETE_COLUMN", "Delete Columns", "Delete columns", "COLUMN", true, true),
    MOVE_COLUMN("MOVE_COLUMN", "Move Columns", "Reorder columns within a board", "COLUMN", true, true),

    CREATE_TASK("CREATE_TASK", "Create Tasks", "Create tasks", "TASK", true, true),
    VIEW_TASK("VIEW_TASK", "View Tasks", "View task details", "TASK", true, true),
    EDIT_TASK("EDIT_TASK", "Edit Tasks", "Edit task fields", "TASK", true, true),
    MOVE_TASK("MOVE_TASK", "Move Tasks", "Move tasks between columns", "TASK", true, true),
    DELETE_TASK("DELETE_TASK", "Delete Tasks", "Delete tasks", "TASK", true, true),
    ARCHIVE_TASK("ARCHIVE_TASK", "Archive Tasks", "Archive and restore tasks", "TASK", true, true),
    ASSIGN_TASK("ASSIGN_TASK", "Assign Tasks", "Assign and unassign task members", "TASK", true, true),

    CREATE_TASK_COMMENT("CREATE_TASK_COMMENT", "Create Task Comments", "Create comments on tasks", "COMMENT", true,
            true),
    EDIT_TASK_COMMENT("EDIT_TASK_COMMENT", "Edit Task Comments", "Edit task comments", "COMMENT", true, true),
    DELETE_TASK_COMMENT("DELETE_TASK_COMMENT", "Delete Task Comments", "Delete task comments", "COMMENT", true, true),

    CREATE_LABEL("CREATE_LABEL", "Create Labels", "Create labels", "LABEL", true, true),
    EDIT_LABEL("EDIT_LABEL", "Edit Labels", "Edit labels", "LABEL", true, true),
    DELETE_LABEL("DELETE_LABEL", "Delete Labels", "Delete labels", "LABEL", true, true),
    APPLY_LABEL_TO_TASK("APPLY_LABEL_TO_TASK", "Apply Labels To Tasks", "Attach labels to tasks", "LABEL", true, true),
    REMOVE_LABEL_FROM_TASK("REMOVE_LABEL_FROM_TASK", "Remove Labels From Tasks", "Detach labels from tasks", "LABEL",
            true, true);

    private final String key;
    private final String name;
    private final String description;
    private final String category;
    private final boolean serverScopeAllowed;
    private final boolean boardScopeAllowed;

    KanbanPermissionCatalog(
            String key,
            String name,
            String description,
            String category,
            boolean serverScopeAllowed,
            boolean boardScopeAllowed) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.category = category;
        this.serverScopeAllowed = serverScopeAllowed;
        this.boardScopeAllowed = boardScopeAllowed;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public boolean isServerScopeAllowed() {
        return serverScopeAllowed;
    }

    public boolean isBoardScopeAllowed() {
        return boardScopeAllowed;
    }

    public static Optional<KanbanPermissionCatalog> fromKey(String key) {
        return Arrays.stream(values())
                .filter(item -> item.key.equals(key))
                .findFirst();
    }
}
