package com.kanbancord_api.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Tasks to add to an existing board, as text: pasted, or the contents of a file.
 *
 * @param format          AUTO (the default) to work it out, or LIST, CSV, TSV, MARKDOWN or KANBANCORD
 * @param defaultColumnId where tasks go that name no column, or name one that cannot be used; the
 *                        board's first column when left out
 * @param createColumns   whether columns the text names that the board does not have are created
 *                        (default true); otherwise their tasks go to the default column
 * @param fields          for a table, what each of its columns holds, as chosen in the preview
 */
public record TaskImportRequest(
        @NotNull @Size(max = TaskImportRequest.MAX_TEXT) String text,
        String format,
        Long defaultColumnId,
        Boolean createColumns,
        @Size(max = 50) List<TaskImportParser.Field> fields) {

    /** About half a megabyte: far more than 500 tasks need, small enough to read in one go. */
    public static final int MAX_TEXT = 500_000;
}
