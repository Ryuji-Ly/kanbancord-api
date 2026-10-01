package com.kanbancord_api.feature;

/**
 * The optional parts of KanbanCord a server can switch on. Without any of them a server is in
 * simple mode: boards, columns, and tasks with a title and description.
 */
public enum Feature {
    LABELS("Labels"),
    PRIORITIES("Priorities"),
    /** Assigning people (and later roles) to tasks. */
    ASSIGNEES("Assignees"),
    COMMENTS("Comments"),
    DUE_DATES("Due dates"),
    /**
     * Custom permission rules. While off, access follows the defaults mapped from Discord permissions;
     * the rules the server has are kept, unused and unchangeable, for when it is switched back on.
     */
    PERMISSIONS("Custom permissions");

    private final String label;

    Feature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether a board can switch the feature off for itself; permissions are managed server-wide. */
    public boolean boardScoped() {
        return this != PERMISSIONS;
    }
}
