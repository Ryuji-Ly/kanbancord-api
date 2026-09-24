package com.kanbancord_api.dto;

import java.util.List;
import java.util.Map;

/**
 * Everything the board page shows, in one response: the board, its columns, tasks and assignments,
 * and what the caller may do on it.
 *
 * @param permissions every board-scope permission key and whether the caller has it
 */
public record BoardSnapshotResponse(
        BoardResponse board,
        List<BoardColumnResponse> columns,
        List<TaskResponse> tasks,
        List<TaskAssignmentResponse> assignments,
        Map<String, PermissionDecisionResponse> permissions) {
}
