package com.kanbancord_api.notify;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads an audit log entry for notifications: which events it is, which task it concerns, and who
 * or what it is about. Audit entries hold a {"created": {...}} or {"deleted": {...}} snapshot, or for
 * edits the changed fields as {"field": {"from": ..., "to": ...}} plus "_column" and "_fromColumn".
 */
public final class AuditClassifier {

    /** Actions of reminder entries, which the queue makes up; they are not in the audit log. */
    public static final String DUE_SOON_ACTION = "TASK_DUE_SOON";
    public static final String OVERDUE_ACTION = "TASK_OVERDUE";

    private AuditClassifier() {
    }

    /** The events an entry stands for; none for entries that are never announced (reordering a column). */
    public static Set<NotificationEvent> eventsOf(String action, Map<String, Object> changes) {
        Map<String, Object> fields = changes == null ? Map.of() : changes;
        return switch (action) {
            case "TASK_CREATED" -> EnumSet.of(NotificationEvent.TASK_CREATED);
            case "TASK_DELETED" -> EnumSet.of(NotificationEvent.TASK_DELETED);
            case DUE_SOON_ACTION -> EnumSet.of(NotificationEvent.DUE_SOON);
            case OVERDUE_ACTION -> EnumSet.of(NotificationEvent.OVERDUE);
            // A move within one column only reorders it: not worth announcing.
            case "TASK_MOVED" -> fields.containsKey("_fromColumn")
                    ? EnumSet.of(NotificationEvent.TASK_MOVED)
                    : EnumSet.noneOf(NotificationEvent.class);
            case "TASK_UPDATED" -> taskUpdateEvents(fields);
            case "TASK_ASSIGNMENT_CREATED" -> EnumSet.of(NotificationEvent.USER_ASSIGNED);
            case "TASK_ASSIGNMENT_DELETED" -> EnumSet.of(NotificationEvent.USER_UNASSIGNED);
            case "TASK_ROLE_ASSIGNED" -> EnumSet.of(NotificationEvent.ROLE_ASSIGNED);
            case "TASK_ROLE_UNASSIGNED" -> EnumSet.of(NotificationEvent.ROLE_UNASSIGNED);
            case "TASK_COMMENT_CREATED" -> EnumSet.of(NotificationEvent.COMMENT_CREATED);
            case "TASK_COMMENT_UPDATED" -> EnumSet.of(NotificationEvent.COMMENT_EDITED);
            case "TASK_COMMENT_DELETED" -> EnumSet.of(NotificationEvent.COMMENT_DELETED);
            case "TASK_LABEL_ADDED" -> EnumSet.of(NotificationEvent.LABEL_ADDED);
            case "TASK_LABEL_REMOVED" -> EnumSet.of(NotificationEvent.LABEL_REMOVED);
            case "COLUMN_CREATED", "COLUMN_UPDATED", "COLUMN_MOVED", "COLUMN_DELETED" ->
                    EnumSet.of(NotificationEvent.COLUMN_CHANGED);
            case "BOARD_CREATED", "BOARD_UPDATED", "BOARD_ARCHIVED", "BOARD_RESTORED", "BOARD_DELETED" ->
                    EnumSet.of(NotificationEvent.BOARD_CHANGED);
            case "LABEL_CREATED", "LABEL_UPDATED", "LABEL_DELETED", "PRIORITY_CREATED", "PRIORITY_UPDATED",
                 "PRIORITY_MOVED", "PRIORITY_DELETED", "BOARD_FEATURES_UPDATED" ->
                    EnumSet.of(NotificationEvent.BOARD_SETTINGS_CHANGED);
            default -> EnumSet.noneOf(NotificationEvent.class);
        };
    }

    private static Set<NotificationEvent> taskUpdateEvents(Map<String, Object> fields) {
        Set<NotificationEvent> events = EnumSet.noneOf(NotificationEvent.class);
        if (fields.containsKey("title")) {
            events.add(NotificationEvent.TASK_TITLE);
        }
        if (fields.containsKey("description")) {
            events.add(NotificationEvent.TASK_DESCRIPTION);
        }
        if (fields.containsKey("dueDate")) {
            events.add(NotificationEvent.TASK_DUE);
        }
        if (fields.containsKey("priorityId")) {
            events.add(NotificationEvent.TASK_PRIORITY);
        }
        if (fields.containsKey("columnId")) {
            events.add(NotificationEvent.TASK_MOVED);
        }
        return events;
    }

    /** The task an entry concerns, or null for entries about something else. */
    public static Long taskIdOf(String entityType, String action, Long entityId, Map<String, Object> changes) {
        if ("TASK".equals(entityType)) {
            return entityId;
        }
        if (!action.startsWith("TASK_")) {
            return null;
        }
        return longOf(snapshotOf(changes).get("taskId"));
    }

    /** For someone being assigned or unassigned, who; for a role, which role. */
    public static Long subjectIdOf(String action, Map<String, Object> changes) {
        Map<String, Object> snapshot = snapshotOf(changes);
        return switch (action) {
            case "TASK_ASSIGNMENT_CREATED", "TASK_ASSIGNMENT_DELETED" -> longOf(snapshot.get("userId"));
            case "TASK_ROLE_ASSIGNED", "TASK_ROLE_UNASSIGNED" -> longOf(snapshot.get("roleId"));
            default -> null;
        };
    }

    /**
     * Entries grouped for delivery: everything about one task together, other board changes by what
     * they concern, and server-wide entries each on their own.
     */
    public static String groupKeyOf(Long serverId, Long boardId, Long taskId, String entityType, Long entityId, Long logId) {
        if (taskId != null) {
            return "task:" + taskId;
        }
        if (boardId != null) {
            return "board:" + boardId + ":" + entityType + ":" + entityId;
        }
        return "server:" + serverId + ":" + logId;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> snapshotOf(Map<String, Object> changes) {
        if (changes == null) {
            return Map.of();
        }
        Object snapshot = changes.containsKey("created") ? changes.get("created") : changes.get("deleted");
        return snapshot instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
