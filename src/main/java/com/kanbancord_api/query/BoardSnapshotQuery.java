package com.kanbancord_api.query;

import com.kanbancord_api.dto.BoardColumnResponse;
import com.kanbancord_api.dto.BoardResponse;
import com.kanbancord_api.dto.BoardSnapshotResponse;
import com.kanbancord_api.dto.PermissionDecisionResponse;
import com.kanbancord_api.dto.TaskAssignmentResponse;
import com.kanbancord_api.dto.TaskResponse;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskAssignmentService;
import com.kanbancord_api.service.TaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads a whole board for the board page in one read-only transaction. */
@Service
@Transactional(readOnly = true)
public class BoardSnapshotQuery {

    private static final List<String> BOARD_PERMISSION_KEYS = Arrays.stream(KanbanPermissionCatalog.values())
            .filter(KanbanPermissionCatalog::isBoardScopeAllowed)
            .map(KanbanPermissionCatalog::getKey)
            .toList();

    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final PermissionEvaluationService permissionEvaluationService;
    private final BoardColumnService boardColumnService;
    private final TaskService taskService;
    private final TaskAssignmentService taskAssignmentService;

    public BoardSnapshotQuery(
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            PermissionEvaluationService permissionEvaluationService,
            BoardColumnService boardColumnService,
            TaskService taskService,
            TaskAssignmentService taskAssignmentService) {
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.permissionEvaluationService = permissionEvaluationService;
        this.boardColumnService = boardColumnService;
        this.taskService = taskService;
        this.taskAssignmentService = taskAssignmentService;
    }

    /**
     * Requires VIEW_BOARD. Tasks and assignments are included only with VIEW_TASK; without it the
     * lists are empty, as the separate endpoints would refuse them.
     */
    public BoardSnapshotResponse load(Long serverId, Long boardId, Long actorUserId) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_BOARD");

        Map<String, PermissionDecisionResponse> permissions = new LinkedHashMap<>();
        permissionEvaluationService.resolveAll(serverId, boardId, actorUserId, BOARD_PERMISSION_KEYS)
                .forEach((key, decision) -> permissions.put(key, PermissionDecisionResponse.from(decision)));
        boolean canViewTasks = permissions.get("VIEW_TASK").isAllowed();

        List<BoardColumnResponse> columns = boardColumnService.findByBoardIdOrdered(boardId).stream()
                .map(BoardColumnResponse::from)
                .toList();
        List<TaskResponse> tasks = canViewTasks
                ? taskService.findByBoardId(boardId).stream().map(TaskResponse::from).toList()
                : List.of();
        List<TaskAssignmentResponse> assignments = canViewTasks
                ? taskAssignmentService.findByBoardId(boardId).stream().map(TaskAssignmentResponse::from).toList()
                : List.of();

        return new BoardSnapshotResponse(BoardResponse.from(board), columns, tasks, assignments, permissions);
    }
}
