package com.kanbancord_api.transfer;

import com.kanbancord_api.board.BoardResponse;

import java.util.Map;

/**
 * The board an import created, and what it brought in.
 *
 * @param imported how many of each were created: columns, tasks, labels, priorities, assignees,
 *                 roles, comments, permissions
 * @param skipped  how many could not be, by kind: {@code assignees} not in this server, {@code roles}
 *                 not in this server, {@code permissions} rules for someone not here or that the
 *                 importer may not set, and {@code commentAuthors}: comments kept, but by people not
 *                 in this server, so they are now the importer's, naming who wrote them
 */
public record BoardImportResult(BoardResponse board, Map<String, Integer> imported, Map<String, Integer> skipped) {
}
