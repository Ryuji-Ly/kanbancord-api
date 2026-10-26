package com.kanbancord_api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What an import would do, or did: the columns tasks go to, the tasks, and what was left out and
 * why. A preview and the import itself read the text the same way, so the preview is exact.
 *
 * @param format  the format the text was read as
 * @param headers a table's headers, for choosing what each holds; empty for other formats
 * @param fields  what each header is read as
 * @param columns the columns that get tasks, in the board's order, new ones last
 * @param tasks   every task, in the order it will be added to its column
 * @param notes   what was changed or left out; {@code code} is for clients to word it, {@code examples}
 *                a few of the values concerned
 */
public record TaskImportPlan(
        TaskImportParser.Format format,
        List<String> headers,
        List<TaskImportParser.Field> fields,
        List<Column> columns,
        List<Task> tasks,
        List<Note> notes) {

    /** @param columnId null for a column the import creates */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(String columnId, String name, boolean created, int tasks) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String title, String description, String column, String dueDate, String priority,
                       List<String> labels) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Note(String code, int count, List<String> examples) {
    }

    /** Codes of {@link Note}. */
    public static final String TOO_MANY = "TOO_MANY";
    public static final String TITLE_SHORTENED = "TITLE_SHORTENED";
    public static final String COLUMNS_NOT_CREATED = "COLUMNS_NOT_CREATED";
    public static final String TOO_MANY_COLUMNS = "TOO_MANY_COLUMNS";
    public static final String DUE_DATES_OFF = "DUE_DATES_OFF";
    public static final String DUE_UNREADABLE = "DUE_UNREADABLE";
    public static final String PRIORITIES_OFF = "PRIORITIES_OFF";
    public static final String PRIORITY_UNKNOWN = "PRIORITY_UNKNOWN";
    public static final String LABELS_OFF = "LABELS_OFF";
    public static final String LABEL_UNKNOWN = "LABEL_UNKNOWN";
    public static final String LABELS_NOT_ALLOWED = "LABELS_NOT_ALLOWED";
}
