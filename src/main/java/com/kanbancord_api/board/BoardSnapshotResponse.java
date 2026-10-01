package com.kanbancord_api.board;

import com.kanbancord_api.label.LabelResponse;
import com.kanbancord_api.label.TaskLabelResponse;
import com.kanbancord_api.permission.PermissionDecisionResponse;
import com.kanbancord_api.priority.BoardPriorityResponse;
import com.kanbancord_api.task.TaskAssignmentResponse;
import com.kanbancord_api.task.TaskRoleAssignmentResponse;
import com.kanbancord_api.task.TaskResponse;

import java.util.List;
import java.util.Map;

/**
 * Everything the board page shows, in one response: the board, its columns, labels and priority
 * levels, tasks with their assignments and labels, and what the caller may do on it.
 *
 * @param permissions every board-scope permission key and whether the caller has it
 * @param features       every optional feature and whether it is on for this board (on for the
 *                       server and not switched off by the board); the lists of a feature that is
 *                       off are empty
 * @param serverFeatures every optional feature and whether the server has it on; a board can only
 *                       switch off what is on here
 * @param threads        whether the board's tasks can have threads, whether they are on, and the
 *                       tasks that have one
 */
public record BoardSnapshotResponse(
        BoardResponse board,
        List<BoardColumnResponse> columns,
        List<TaskResponse> tasks,
        List<TaskAssignmentResponse> assignments,
        List<TaskRoleAssignmentResponse> roleAssignments,
        List<LabelResponse> labels,
        List<TaskLabelResponse> taskLabels,
        List<BoardPriorityResponse> priorities,
        Map<String, PermissionDecisionResponse> permissions,
        Map<String, Boolean> features,
        Map<String, Boolean> serverFeatures,
        /** Tasks on this board the person asking follows; always empty for a board post. */
        List<Long> followedTaskIds,
        TaskThreads threads) {

    /**
     * {@code available}: a feed covers the board, so its tasks can have threads; {@code enabled}: threads
     * are on and working; {@code threadIds}: by task id, the thread of each task that has one (in the
     * channel threads go in now).
     */
    public record TaskThreads(boolean available, boolean enabled, Map<String, String> threadIds) {
    }
}
