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
     * Editing permission rules. While off, the rules the server already has keep applying (for a
     * new server, the defaults mapped from Discord permissions); only changing them is refused.
     */
    PERMISSIONS("Custom permissions");

    private final String label;

    Feature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
