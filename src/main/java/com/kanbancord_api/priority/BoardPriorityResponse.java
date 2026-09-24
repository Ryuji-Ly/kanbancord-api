package com.kanbancord_api.priority;

/** A priority level of a board. {@code position} 1 is the most urgent. */
public record BoardPriorityResponse(Long priorityId, Long boardId, String name, String color, Integer position) {

    public static BoardPriorityResponse from(BoardPriority priority) {
        return new BoardPriorityResponse(priority.getPriorityId(), priority.getBoardId(), priority.getName(),
                priority.getColor(), priority.getPosition());
    }
}
