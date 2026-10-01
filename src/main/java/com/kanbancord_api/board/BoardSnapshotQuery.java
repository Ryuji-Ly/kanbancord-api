package com.kanbancord_api.board;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import com.kanbancord_api.label.LabelResponse;
import com.kanbancord_api.label.LabelService;
import com.kanbancord_api.label.TaskLabelResponse;
import com.kanbancord_api.label.TaskLabelService;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.permission.PermissionDecisionResponse;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.priority.BoardPriorityResponse;
import com.kanbancord_api.priority.BoardPriorityService;
import com.kanbancord_api.task.TaskAssignmentResponse;
import com.kanbancord_api.task.TaskAssignmentService;
import com.kanbancord_api.task.TaskRoleAssignmentRepository;
import com.kanbancord_api.task.TaskRoleAssignmentResponse;
import com.kanbancord_api.task.TaskResponse;
import com.kanbancord_api.task.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads a whole board for the board page in one read-only transaction. */
@Service
@Transactional(readOnly = true)
public class BoardSnapshotQuery {

    private static final List<String> BOARD_PERMISSION_KEYS = Arrays.stream(KanbanPermissionCatalog.values())
            .filter(KanbanPermissionCatalog::isBoardScopeAllowed)
            .map(KanbanPermissionCatalog::getKey)
            .toList();

    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final PermissionEvaluationService permissionEvaluationService;
    private final BoardColumnService boardColumnService;
    private final TaskService taskService;
    private final TaskAssignmentService taskAssignmentService;
    private final LabelService labelService;
    private final TaskLabelService taskLabelService;
    private final BoardPriorityService boardPriorityService;
    private final ServerFeatureService serverFeatureService;
    private final TaskRoleAssignmentRepository taskRoleAssignmentRepository;
    private final JdbcTemplate jdbcTemplate;

    public BoardSnapshotQuery(
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            PermissionEvaluationService permissionEvaluationService,
            BoardColumnService boardColumnService,
            TaskService taskService,
            TaskAssignmentService taskAssignmentService,
            LabelService labelService,
            TaskLabelService taskLabelService,
            BoardPriorityService boardPriorityService,
            ServerFeatureService serverFeatureService,
            TaskRoleAssignmentRepository taskRoleAssignmentRepository,
            JdbcTemplate jdbcTemplate) {
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.permissionEvaluationService = permissionEvaluationService;
        this.boardColumnService = boardColumnService;
        this.taskService = taskService;
        this.taskAssignmentService = taskAssignmentService;
        this.labelService = labelService;
        this.taskLabelService = taskLabelService;
        this.boardPriorityService = boardPriorityService;
        this.serverFeatureService = serverFeatureService;
        this.taskRoleAssignmentRepository = taskRoleAssignmentRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Requires VIEW_BOARD. Tasks, assignments and the labels on tasks are included only with VIEW_TASK;
     * without it the lists are empty, as the separate endpoints would refuse them.
     */
    public BoardSnapshotResponse load(Long serverId, Long boardId, Long actorUserId) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_BOARD");

        Map<String, PermissionDecisionResponse> permissions = new LinkedHashMap<>();
        permissionEvaluationService.resolveAll(serverId, boardId, actorUserId, BOARD_PERMISSION_KEYS)
                .forEach((key, decision) -> permissions.put(key, PermissionDecisionResponse.from(decision)));
        boolean canViewTasks = permissions.get("VIEW_TASK").isAllowed();
        List<Long> followed = canViewTasks
                ? jdbcTemplate.queryForList("""
                        SELECT f.task_id FROM task_followers f JOIN tasks t ON t.task_id = f.task_id
                        WHERE t.board_id = ? AND f.user_id = ? ORDER BY f.task_id
                        """, Long.class, boardId, actorUserId)
                : List.of();
        return build(serverId, boardId, board, permissions, canViewTasks, followed);
    }

    /**
     * The whole board regardless of who is looking, for a board post in Discord: whoever posted it
     * chose to show the board to that channel. Carries no permissions, since nobody in particular is
     * asking.
     */
    public BoardSnapshotResponse loadComplete(Long serverId, Long boardId) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        return build(serverId, boardId, board, Map.of(), true, List.of());
    }

    private BoardSnapshotResponse build(Long serverId, Long boardId, Board board,
                                        Map<String, PermissionDecisionResponse> permissions, boolean canViewTasks,
                                        List<Long> followedTaskIds) {
        List<BoardColumnResponse> columns = boardColumnService.findByBoardIdOrdered(boardId).stream()
                .map(BoardColumnResponse::from)
                .toList();
        List<TaskResponse> tasks = canViewTasks
                ? taskService.findByBoardId(boardId).stream().map(TaskResponse::from).toList()
                : List.of();
        Set<Feature> enabled = serverFeatureService.enabled(serverId, boardId);
        Set<Feature> serverEnabled = serverFeatureService.enabled(serverId);
        List<TaskAssignmentResponse> assignments = canViewTasks && enabled.contains(Feature.ASSIGNEES)
                ? taskAssignmentService.findByBoardId(boardId).stream().map(TaskAssignmentResponse::from).toList()
                : List.of();

        List<TaskRoleAssignmentResponse> roleAssignments = canViewTasks && enabled.contains(Feature.ASSIGNEES)
                ? taskRoleAssignmentRepository.findByBoardId(boardId).stream().map(TaskRoleAssignmentResponse::from).toList()
                : List.of();

        boolean labelsOn = enabled.contains(Feature.LABELS);
        List<LabelResponse> labels = labelsOn
                ? labelService.findByBoardId(boardId).stream().map(LabelResponse::from).toList()
                : List.of();
        List<TaskLabelResponse> taskLabels = canViewTasks && labelsOn
                ? taskLabelService.findByBoardId(boardId).stream().map(TaskLabelResponse::from).toList()
                : List.of();

        List<BoardPriorityResponse> priorities = enabled.contains(Feature.PRIORITIES)
                ? boardPriorityService.findByBoardId(boardId).stream().map(BoardPriorityResponse::from).toList()
                : List.of();

        Map<String, Boolean> features = new LinkedHashMap<>();
        Map<String, Boolean> serverFeatures = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            features.put(feature.name(), enabled.contains(feature));
            serverFeatures.put(feature.name(), serverEnabled.contains(feature));
        }

        return new BoardSnapshotResponse(BoardResponse.from(board), columns, tasks, assignments, roleAssignments, labels,
                taskLabels, priorities, permissions, features, serverFeatures, followedTaskIds,
                taskThreads(serverId, boardId, canViewTasks));
    }

    /** Whether a feed covers the board, whether threads are on and working, and the tasks' threads. */
    private BoardSnapshotResponse.TaskThreads taskThreads(Long serverId, Long boardId, boolean canViewTasks) {
        String covering = "SELECT channel_id FROM notification_feeds WHERE server_id = ? "
                + "AND (cardinality(board_ids) = 0 OR ? = ANY (board_ids))";
        boolean available = !jdbcTemplate.queryForList(covering, Long.class, serverId, boardId).isEmpty();
        List<Long> channel = available
                ? jdbcTemplate.queryForList("SELECT channel_id FROM board_thread_settings WHERE board_id = ? AND channel_id IN ("
                        + covering + ")", Long.class, boardId, serverId, boardId)
                : List.of();
        Map<String, String> threadIds = new LinkedHashMap<>();
        if (!channel.isEmpty() && canViewTasks) {
            jdbcTemplate.query("""
                    SELECT th.task_id, th.thread_id FROM task_threads th JOIN tasks t ON t.task_id = th.task_id
                    WHERE t.board_id = ? AND th.channel_id = ?
                    """, row -> {
                threadIds.put(String.valueOf(row.getLong(1)), String.valueOf(row.getLong(2)));
            }, boardId, channel.get(0));
        }
        return new BoardSnapshotResponse.TaskThreads(available, !channel.isEmpty(), threadIds);
    }
}
