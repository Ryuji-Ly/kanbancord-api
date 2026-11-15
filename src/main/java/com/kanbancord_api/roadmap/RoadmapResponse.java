package com.kanbancord_api.roadmap;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * KanbanCord's own board, as anyone may see it: its columns and tasks with their labels and
 * priority levels. Nothing about people is in it (no assignees or creators), except the names of whoever comments,
 * which are public: the comments are read separately, per task.
 *
 * @param issuesColumnId the column anyone signed in may add an issue to; null when the board has none
 * @param commentsOn     whether its tasks have comments, which anyone may read and anyone signed in may write
 */
public record RoadmapResponse(
        String name,
        String description,
        String issuesColumnId,
        List<Column> columns,
        List<Label> labels,
        List<Priority> priorities,
        List<Task> tasks,
        boolean commentsOn) {

    public record Column(String id, String name, String color) {
    }

    public record Label(String id, String name, String color) {
    }

    /** {@code position} 1 is the most urgent, shown as P1. */
    public record Priority(String id, String name, String color, int position) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String id, String columnId, String title, String description, List<String> labelIds,
                       String priorityId, LocalDateTime createdAt, int commentCount) {
    }

    /** A comment on a roadmap task, with its writer's Discord name (display name, else username). */
    public record Comment(String id, String authorName, String content, LocalDateTime createdAt, boolean edited) {
    }
}
