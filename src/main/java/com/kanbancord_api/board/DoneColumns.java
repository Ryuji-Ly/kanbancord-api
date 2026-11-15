package com.kanbancord_api.board;

import java.util.Comparator;
import java.util.List;

/**
 * Which of a board's columns count as done: the ones marked so, or, when none is, the board's last column.
 * Tasks in them get no due reminders, count as done in label progress, and ticked items imported into the board
 * go to the first of them. Archived tasks count as done too, wherever they are.
 */
public final class DoneColumns {

    private DoneColumns() {
    }

    /** The done columns, in board order; never empty for a board with columns. */
    public static List<BoardColumn> of(List<BoardColumn> columns) {
        List<BoardColumn> inOrder = columns.stream()
                .sorted(Comparator.comparing(BoardColumn::getPosition, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<BoardColumn> marked = inOrder.stream().filter(BoardColumn::isCountsAsDone).toList();
        if (!marked.isEmpty() || inOrder.isEmpty()) {
            return marked;
        }
        return List.of(inOrder.get(inOrder.size() - 1));
    }

    /** SQL that is true when the task (by its table alias) is in a column that counts as done. */
    public static String sqlTaskIsDone(String task) {
        return """
                %1$s.column_id IN (
                    SELECT dc.column_id FROM columns dc
                    WHERE dc.board_id = %1$s.board_id
                      AND (dc.counts_as_done
                           OR (NOT EXISTS (SELECT 1 FROM columns dm WHERE dm.board_id = dc.board_id AND dm.counts_as_done)
                               AND dc.column_id = (SELECT dl.column_id FROM columns dl WHERE dl.board_id = dc.board_id
                                                   ORDER BY dl.position DESC LIMIT 1))))""".formatted(task);
    }
}
