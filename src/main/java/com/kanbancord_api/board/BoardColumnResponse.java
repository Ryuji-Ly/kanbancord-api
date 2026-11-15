package com.kanbancord_api.board;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BoardColumnResponse(
        Long columnId,
        Long boardId,
        String name,
        BigDecimal position,
        String color,
        Integer wipLimit,
        /** Marked as done. Whether its tasks count as done also depends on the other columns: see DoneColumns. */
        boolean countsAsDone,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static BoardColumnResponse from(BoardColumn column) {
        return new BoardColumnResponse(
                column.getColumnId(),
                column.getBoard().getBoardId(),
                column.getName(),
                column.getPosition(),
                column.getColor(),
                column.getWipLimit(),
                column.isCountsAsDone(),
                column.getCreatedAt(),
                column.getUpdatedAt());
    }
}
