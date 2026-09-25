package com.kanbancord_api.board;

import com.kanbancord_api.label.LabelResponse;
import com.kanbancord_api.label.TaskLabelResponse;
import com.kanbancord_api.permission.PermissionDecisionResponse;
import com.kanbancord_api.priority.BoardPriorityResponse;
import com.kanbancord_api.task.TaskAssignmentResponse;
import com.kanbancord_api.task.TaskResponse;

import java.util.List;
import java.util.Map;

/**
 * Everything the board page shows, in one response: the board, its columns, labels and priority
 * levels, tasks with their assignments and labels, and what the caller may do on it.
 *
 * @param permissions every board-scope permission key and whether the caller has it
 * @param features    every optional feature and whether the server has it on; the lists of a
 *                    feature that is off are empty
 */
public record BoardSnapshotResponse(
        BoardResponse board,
        List<BoardColumnResponse> columns,
        List<TaskResponse> tasks,
        List<TaskAssignmentResponse> assignments,
        List<LabelResponse> labels,
        List<TaskLabelResponse> taskLabels,
        List<BoardPriorityResponse> priorities,
        Map<String, PermissionDecisionResponse> permissions,
        Map<String, Boolean> features) {
}
