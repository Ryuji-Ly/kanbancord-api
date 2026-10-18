package com.kanbancord_api.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A board file to import as a new board.
 *
 * @param parts the optional parts of the file to bring in; null brings in every part the file has
 * @param name  the new board's name; the file's when absent. A number is added when the server
 *              already has a board by that name.
 */
public record BoardImportRequest(
        @NotNull @Valid BoardExport document,
        List<String> parts,
        @Size(max = 100) String name) {
}
