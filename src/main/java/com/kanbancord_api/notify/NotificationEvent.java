package com.kanbancord_api.notify;

/**
 * What can be announced in a feed channel or by direct message, grouped into categories that can be
 * switched on and off together. Each audit log entry maps to none, one or several of these (an edit
 * can change a task's title and due date at once).
 *
 * <p>The defaults are what a new feed starts with, and what a person gets by DM before changing
 * anything. Direct messages are only about tasks: board structure is announced in feeds only.
 */
public enum NotificationEvent {

    TASK_CREATED(Category.TASKS, "Task created", true, false),
    TASK_DELETED(Category.TASKS, "Task deleted", true, true),
    TASK_MOVED(Category.TASKS, "Task moved to another column", true, true),
    TASK_TITLE(Category.TASKS, "Title changed", true, false),
    TASK_DESCRIPTION(Category.TASKS, "Description changed", true, false),
    TASK_DUE(Category.TASKS, "Due date changed", true, true),
    TASK_PRIORITY(Category.TASKS, "Priority changed", true, false),

    USER_ASSIGNED(Category.PEOPLE, "Someone assigned", true, true),
    USER_UNASSIGNED(Category.PEOPLE, "Someone unassigned", true, true),
    ROLE_ASSIGNED(Category.PEOPLE, "Role assigned", true, null),
    ROLE_UNASSIGNED(Category.PEOPLE, "Role unassigned", true, null),

    COMMENT_CREATED(Category.COMMENTS, "New comment", true, true),
    COMMENT_EDITED(Category.COMMENTS, "Comment edited", false, false),
    COMMENT_DELETED(Category.COMMENTS, "Comment deleted", false, false),

    LABEL_ADDED(Category.LABELS, "Label added to a task", true, false),
    LABEL_REMOVED(Category.LABELS, "Label removed from a task", true, false),

    COLUMN_CHANGED(Category.BOARD, "Columns created, renamed, moved or deleted", true, null),
    BOARD_CHANGED(Category.BOARD, "Board created, renamed, archived or deleted", true, null),
    BOARD_SETTINGS_CHANGED(Category.BOARD, "Labels, priority levels and board features changed", false, null);

    /** Groups of events, switched on and off together, each with its own mention setting. */
    public enum Category {
        TASKS("Tasks", false),
        PEOPLE("People", true),
        COMMENTS("Comments", false),
        LABELS("Labels", false),
        BOARD("Board structure", false);

        private final String label;
        private final boolean mentionByDefault;

        Category(String label, boolean mentionByDefault) {
            this.label = label;
            this.mentionByDefault = mentionByDefault;
        }

        public String label() {
            return label;
        }

        /** Whether a new feed mentions the people involved in this category's events. */
        public boolean mentionByDefault() {
            return mentionByDefault;
        }
    }

    private final Category category;
    private final String label;
    private final boolean feedDefault;
    /** null: never sent by direct message. */
    private final Boolean dmDefault;

    NotificationEvent(Category category, String label, boolean feedDefault, Boolean dmDefault) {
        this.category = category;
        this.label = label;
        this.feedDefault = feedDefault;
        this.dmDefault = dmDefault;
    }

    public Category category() {
        return category;
    }

    public String label() {
        return label;
    }

    public boolean feedDefault() {
        return feedDefault;
    }

    public boolean canDm() {
        return dmDefault != null;
    }

    public boolean dmDefault() {
        return Boolean.TRUE.equals(dmDefault);
    }

    /** Events about someone being assigned or unassigned: sent to that person, not to the task's people. */
    public boolean isAboutAssignee() {
        return this == USER_ASSIGNED || this == USER_UNASSIGNED;
    }
}
