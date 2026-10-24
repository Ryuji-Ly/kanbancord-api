package com.kanbancord_api.roadmap;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * KanbanCord's own board, as anyone may see it: its columns and tasks with their labels and
 * priority levels. Nothing about people is in it: no assignees, creators or comments.
 *
 * @param issuesColumnId the column anyone signed in may add an issue to; null when the board has none
 */
public record RoadmapResponse(
        String name,
        String description,
        String issuesColumnId,
        List<Column> columns,
        List<Label> labels,
        List<Priority> priorities,
        List<Task> tasks) {

    public record Column(String id, String name, String color) {
    }

    public record Label(String id, String name, String color) {
    }

    /** {@code position} 1 is the most urgent, shown as P1. */
    public record Priority(String id, String name, String color, int position) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String id, String columnId, String title, String description, List<String> labelIds,
                       String priorityId, LocalDateTime createdAt) {
    }
}
