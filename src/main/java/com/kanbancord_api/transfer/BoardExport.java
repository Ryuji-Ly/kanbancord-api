package com.kanbancord_api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/**
 * A board as a file: what an export produces and an import reads. The board, its columns and tasks
 * are always there; every other part is in the file only when it was chosen, and is then left out
 * everywhere, also from the tasks that use it ({@code null} lists are not written).
 *
 * <p>Ids are the original ones, as text, and only link the parts of the file together: an import
 * creates everything anew. People and roles keep their Discord ids, with their names as they were,
 * so the file can be read on its own.
 *
 * @param format  always {@value #FORMAT}
 * @param version the format's version; an import refuses files newer than {@value #VERSION}
 * @param parts   the optional parts the file contains, from {@link #PARTS}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BoardExport(
        @NotBlank String format,
        @NotNull Integer version,
        String exportedAt,
        Set<String> parts,
        @NotNull @Valid Board board,
        @NotNull @Size(max = MAX_COLUMNS) List<@Valid Column> columns,
        @Size(max = MAX_LABELS) List<@Valid Label> labels,
        @Size(max = MAX_PRIORITIES) List<@Valid Priority> priorities,
        @NotNull @Size(max = MAX_TASKS) List<@Valid Task> tasks,
        @Size(max = MAX_RULES) List<@Valid Rule> permissions) {

    public static final String FORMAT = "kanbancord-board";
    public static final int VERSION = 1;

    public static final String LABELS = "labels";
    public static final String PRIORITIES = "priorities";
    public static final String ASSIGNEES = "assignees";
    public static final String COMMENTS = "comments";
    public static final String PERMISSIONS = "permissions";
    /** Every optional part, in the order they are offered. */
    public static final List<String> PARTS = List.of(LABELS, PRIORITIES, ASSIGNEES, COMMENTS, PERMISSIONS);

    static final int MAX_COLUMNS = 50;
    static final int MAX_LABELS = 200;
    static final int MAX_PRIORITIES = 50;
    static final int MAX_TASKS = 5_000;
    static final int MAX_COMMENTS_PER_TASK = 500;
    static final int MAX_RULES = 500;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Board(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(@NotBlank String id, @NotBlank @Size(max = 50) String name, @Size(max = 7) String color,
                         Integer wipLimit) {
    }

    public record Label(@NotBlank String id, @NotBlank @Size(max = 50) String name, String color) {
    }

    /** Levels are listed most urgent first. */
    public record Priority(@NotBlank String id, @NotBlank @Size(max = 50) String name, String color) {
    }

    /** Someone as they were when the board was exported. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Person(@NotBlank String userId, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Role(@NotBlank String roleId, String name) {
    }

    /**
     * Tasks are listed in the order they have in their column.
     *
     * @param column      the id of its column in the file
     * @param dueDate     the user's own wall-clock time, {@code 2026-10-20T18:00:00}
     * @param completedAt when it was completed, if it was
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(
            @NotBlank String id,
            String column,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 100_000) String description,
            String dueDate,
            Boolean archived,
            String completedAt,
            String priority,
            List<String> labels,
            List<@Valid Person> assignees,
            List<@Valid Role> roles,
            @Size(max = MAX_COMMENTS_PER_TASK) List<@Valid Comment> comments) {
    }

    /** Comments are listed oldest first. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Comment(@NotBlank String id, String replyTo, @Valid Person author,
                          @NotBlank @Size(max = 2000) String content, String createdAt) {
    }

    /**
     * A permission rule of the board.
     *
     * @param subjectType USER, ROLE or DISCORD_PERMISSION
     * @param subjectId   the user's or role's Discord id, or the Discord permission's bit
     * @param subjectName the person's or role's name when exported, to read the file by
     * @param permission  the KanbanCord permission's key, such as EDIT_TASK
     * @param state       ALLOW or DENY
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(@NotBlank String subjectType, @NotBlank String subjectId, String subjectName,
                       @NotBlank String permission, @NotBlank String state, Integer priority) {
    }
}
