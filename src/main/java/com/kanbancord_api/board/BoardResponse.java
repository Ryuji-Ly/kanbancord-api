package com.kanbancord_api.board;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record BoardResponse(
        Long boardId,
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        String name,
        String description,
        Boolean isArchived,
        @JsonSerialize(using = ToStringSerializer.class) Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static BoardResponse from(Board board) {
        return new BoardResponse(
                board.getBoardId(),
                board.getServer().getServerId(),
                board.getName(),
                board.getDescription(),
                board.getIsArchived(),
                board.getCreatedBy() == null ? null : board.getCreatedBy().getUserId(),
                board.getCreatedAt(),
                board.getUpdatedAt());
    }
}
