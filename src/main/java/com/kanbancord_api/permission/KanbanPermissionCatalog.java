package com.kanbancord_api.permission;

import java.util.Arrays;
import java.util.Optional;

public enum KanbanPermissionCatalog {

        ADMIN("ADMIN", "Administrator", "Grants all Kanban permissions across all scopes", "SERVER", true, true,
                        PermissionRank.ADMIN),

        VIEW_SERVER("VIEW_SERVER", "View Server", "View server overview and metadata", "SERVER", true, false,
                        PermissionRank.READONLY),
        MANAGE_SERVER_PERMISSIONS("MANAGE_SERVER_PERMISSIONS", "Manage Server Permissions",
                        "Configure Kanban permission rules for this server (subjects, states, overrides)", "SERVER",
                        true, false, PermissionRank.SERVER_MANAGE),
        VIEW_AUDIT_LOG("VIEW_AUDIT_LOG", "View Audit Log", "Read audit events", "SERVER", true, false,
                        PermissionRank.READONLY),

        CREATE_BOARD("CREATE_BOARD", "Create Board", "Create new boards", "BOARD", true, false,
                        PermissionRank.BOARD_MANAGE),
        VIEW_BOARD("VIEW_BOARD", "View Board", "View board content", "BOARD", true, true,
                        PermissionRank.READONLY),
        EDIT_BOARD_DETAILS("EDIT_BOARD_DETAILS", "Edit Board Details", "Edit board name, description, and state",
                        "BOARD",
                        true, true, PermissionRank.BOARD_MANAGE),
        EDIT_BOARD_PERMISSIONS("EDIT_BOARD_PERMISSIONS", "Edit Board Permissions",
                        "Manage board-scoped permission overrides", "BOARD", true, true,
                        PermissionRank.BOARD_MANAGE),
        ARCHIVE_BOARD("ARCHIVE_BOARD", "Archive Board", "Archive and restore boards", "BOARD", true, true,
                        PermissionRank.BOARD_MANAGE),
        DELETE_BOARD("DELETE_BOARD", "Delete Board", "Delete boards", "BOARD", true, true,
                        PermissionRank.BOARD_MANAGE),

        CREATE_COLUMN("CREATE_COLUMN", "Create Columns", "Create board columns", "COLUMN", true, true,
                        PermissionRank.BOARD_MANAGE),
        EDIT_COLUMN("EDIT_COLUMN", "Edit Columns", "Edit column properties", "COLUMN", true, true,
                        PermissionRank.BOARD_MANAGE),
        DELETE_COLUMN("DELETE_COLUMN", "Delete Columns", "Delete columns", "COLUMN", true, true,
                        PermissionRank.BOARD_MANAGE),
        MOVE_COLUMN("MOVE_COLUMN", "Move Columns", "Reorder columns within a board", "COLUMN", true, true,
                        PermissionRank.BOARD_MANAGE),

        CREATE_TASK("CREATE_TASK", "Create Tasks", "Create tasks", "TASK", true, true, PermissionRank.STANDARD),
        VIEW_TASK("VIEW_TASK", "View Tasks", "View task details", "TASK", true, true, PermissionRank.READONLY),
        EDIT_TASK("EDIT_TASK", "Edit Tasks", "Edit task fields", "TASK", true, true, PermissionRank.STANDARD),
        MOVE_TASK("MOVE_TASK", "Move Tasks", "Move tasks between columns", "TASK", true, true,
                        PermissionRank.STANDARD),
        DELETE_TASK("DELETE_TASK", "Delete Tasks", "Delete tasks", "TASK", true, true, PermissionRank.STANDARD),
        ARCHIVE_TASK("ARCHIVE_TASK", "Archive Tasks", "Archive and restore tasks", "TASK", true, true,
                        PermissionRank.STANDARD),
        ASSIGN_TASK_SELF("ASSIGN_TASK_SELF", "Assign Tasks to Self", "Assign yourself to a task", "TASK", true,
                        true, PermissionRank.STANDARD),
        ASSIGN_TASK_OTHERS("ASSIGN_TASK_OTHERS", "Assign Tasks to Others", "Assign other members to a task", "TASK",
                        true, true, PermissionRank.STANDARD),

        CREATE_TASK_COMMENT("CREATE_TASK_COMMENT", "Create Task Comments", "Create comments on tasks", "COMMENT", true,
                        true, PermissionRank.STANDARD),
        DELETE_TASK_COMMENT("DELETE_TASK_COMMENT", "Delete Task Comments", "Delete task comments", "COMMENT", true,
                        true, PermissionRank.STANDARD),

        CREATE_LABEL("CREATE_LABEL", "Create Labels", "Create labels", "LABEL", true, true,
                        PermissionRank.BOARD_MANAGE),
        EDIT_LABEL("EDIT_LABEL", "Edit Labels", "Edit labels", "LABEL", true, true, PermissionRank.BOARD_MANAGE),
        DELETE_LABEL("DELETE_LABEL", "Delete Labels", "Delete labels", "LABEL", true, true,
                        PermissionRank.BOARD_MANAGE),
        APPLY_LABEL_TO_TASK("APPLY_LABEL_TO_TASK", "Apply Labels To Tasks", "Attach labels to tasks", "LABEL", true,
                        true, PermissionRank.STANDARD),
        REMOVE_LABEL_FROM_TASK("REMOVE_LABEL_FROM_TASK", "Remove Labels From Tasks", "Detach labels from tasks",
                        "LABEL",
                        true, true, PermissionRank.STANDARD),
        MANAGE_PRIORITIES("MANAGE_PRIORITIES", "Manage Priorities",
                        "Create, edit, reorder and delete priority levels", "LABEL", true, true,
                        PermissionRank.BOARD_MANAGE);

        private final String key;
        private final String name;
        private final String description;
        private final String category;
        private final boolean serverScopeAllowed;
        private final boolean boardScopeAllowed;
        private final PermissionRank rank;

        KanbanPermissionCatalog(
                        String key,
                        String name,
                        String description,
                        String category,
                        boolean serverScopeAllowed,
                        boolean boardScopeAllowed,
                        PermissionRank rank) {
                this.key = key;
                this.name = name;
                this.description = description;
                this.category = category;
                this.serverScopeAllowed = serverScopeAllowed;
                this.boardScopeAllowed = boardScopeAllowed;
                this.rank = rank;
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

        public PermissionRank getRank() {
                return rank;
        }

        public static Optional<KanbanPermissionCatalog> fromKey(String key) {
                return Arrays.stream(values())
                                .filter(item -> item.key.equals(key))
                                .findFirst();
        }
}
