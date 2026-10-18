package com.kanbancord_api.transfer;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.board.BoardColumnService;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.label.LabelService;
import com.kanbancord_api.label.TaskLabel;
import com.kanbancord_api.label.TaskLabelService;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionService;
import com.kanbancord_api.priority.BoardPriorityService;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.RoleService;
import com.kanbancord_api.task.Task;
import com.kanbancord_api.task.TaskAssignment;
import com.kanbancord_api.task.TaskAssignmentService;
import com.kanbancord_api.task.TaskRoleAssignmentRepository;
import com.kanbancord_api.task.TaskService;
import com.kanbancord_api.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A board as a {@link BoardExport}, with only the optional parts asked for. The board and its tasks
 * need permission to view them; the board's permission rules need permission to edit them.
 */
@Service
@Transactional(readOnly = true)
public class BoardExportQuery {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final BoardColumnService boardColumnService;
    private final TaskService taskService;
    private final LabelService labelService;
    private final TaskLabelService taskLabelService;
    private final BoardPriorityService boardPriorityService;
    private final TaskAssignmentService taskAssignmentService;
    private final TaskRoleAssignmentRepository taskRoleAssignmentRepository;
    private final RoleService roleService;
    private final PermissionService permissionService;
    private final JdbcTemplate jdbcTemplate;

    public BoardExportQuery(
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            BoardColumnService boardColumnService,
            TaskService taskService,
            LabelService labelService,
            TaskLabelService taskLabelService,
            BoardPriorityService boardPriorityService,
            TaskAssignmentService taskAssignmentService,
            TaskRoleAssignmentRepository taskRoleAssignmentRepository,
            RoleService roleService,
            PermissionService permissionService,
            JdbcTemplate jdbcTemplate) {
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.boardColumnService = boardColumnService;
        this.taskService = taskService;
        this.labelService = labelService;
        this.taskLabelService = taskLabelService;
        this.boardPriorityService = boardPriorityService;
        this.taskAssignmentService = taskAssignmentService;
        this.taskRoleAssignmentRepository = taskRoleAssignmentRepository;
        this.roleService = roleService;
        this.permissionService = permissionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The parts asked for, known ones only and in their usual order. */
    static Set<String> partsOf(Iterable<String> requested) {
        Set<String> asked = new LinkedHashSet<>();
        if (requested != null) {
            requested.forEach(part -> asked.add(part == null ? "" : part.trim().toLowerCase()));
        }
        for (String part : asked) {
            if (!part.isEmpty() && !BoardExport.PARTS.contains(part)) {
                throw new BadRequestException("Unknown part: " + part + ". Parts are " + String.join(", ", BoardExport.PARTS));
            }
        }
        Set<String> parts = new LinkedHashSet<>();
        BoardExport.PARTS.stream().filter(asked::contains).forEach(parts::add);
        return parts;
    }

    public BoardExport export(Long serverId, Long boardId, Long actorUserId, Iterable<String> requestedParts) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_BOARD");
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_TASK");
        Set<String> parts = partsOf(requestedParts);
        if (parts.contains(BoardExport.PERMISSIONS)) {
            authorizer.requireBoardPermission(actorUserId, serverId, boardId, "EDIT_BOARD_PERMISSIONS");
        }

        List<BoardColumn> columns = boardColumnService.findByBoardIdOrdered(boardId);
        Map<Long, Integer> columnOrder = new LinkedHashMap<>();
        columns.forEach(column -> columnOrder.put(column.getColumnId(), columnOrder.size()));
        List<Task> tasks = taskService.findByBoardId(boardId).stream()
                .sorted(Comparator.<Task, Integer>comparing(task -> columnOrder.getOrDefault(task.getColumn().getColumnId(), 0))
                        .thenComparing(Task::getPosition, Comparator.nullsLast(BigDecimal::compareTo))
                        .thenComparing(Task::getTaskId))
                .toList();

        boolean labels = parts.contains(BoardExport.LABELS);
        boolean priorities = parts.contains(BoardExport.PRIORITIES);
        boolean assignees = parts.contains(BoardExport.ASSIGNEES);
        boolean comments = parts.contains(BoardExport.COMMENTS);

        Map<Long, List<String>> labelsByTask = labels
                ? taskLabelService.findByBoardId(boardId).stream().collect(Collectors.groupingBy(
                        (TaskLabel link) -> link.getTask().getTaskId(),
                        Collectors.mapping(link -> id(link.getLabel().getLabelId()), Collectors.toList())))
                : Map.of();
        Map<Long, List<BoardExport.Person>> peopleByTask = assignees
                ? taskAssignmentService.findByBoardId(boardId).stream().collect(Collectors.groupingBy(
                        (TaskAssignment assignment) -> assignment.getTask().getTaskId(),
                        Collectors.mapping(assignment -> person(assignment.getUser()), Collectors.toList())))
                : Map.of();
        Map<Long, String> roleNames = assignees || parts.contains(BoardExport.PERMISSIONS)
                ? roleService.findByServerId(serverId).stream().collect(Collectors.toMap(Role::getRoleId, Role::getName))
                : Map.of();
        Map<Long, List<BoardExport.Role>> rolesByTask = assignees
                ? taskRoleAssignmentRepository.findByBoardId(boardId).stream().collect(Collectors.groupingBy(
                        assignment -> assignment.getTaskId(),
                        Collectors.mapping(assignment -> new BoardExport.Role(id(assignment.getRoleId()),
                                roleNames.get(assignment.getRoleId())), Collectors.toList())))
                : Map.of();
        Map<Long, List<BoardExport.Comment>> commentsByTask = comments ? comments(boardId) : Map.of();

        List<BoardExport.Task> exportedTasks = tasks.stream().map(task -> new BoardExport.Task(
                id(task.getTaskId()),
                id(task.getColumn().getColumnId()),
                task.getTitle(),
                task.getDescription(),
                time(task.getDueDate()),
                Boolean.TRUE.equals(task.getIsArchived()) ? true : null,
                time(task.getCompletedAt()),
                priorities && task.getPriorityId() != null ? id(task.getPriorityId()) : null,
                labels ? labelsByTask.getOrDefault(task.getTaskId(), List.of()) : null,
                assignees ? peopleByTask.getOrDefault(task.getTaskId(), List.of()) : null,
                assignees ? rolesByTask.getOrDefault(task.getTaskId(), List.of()) : null,
                comments ? commentsByTask.getOrDefault(task.getTaskId(), List.of()) : null)).toList();

        return new BoardExport(
                BoardExport.FORMAT,
                BoardExport.VERSION,
                LocalDateTime.now(ZoneOffset.UTC).format(TIME) + "Z",
                parts,
                new BoardExport.Board(board.getName(), board.getDescription()),
                columns.stream().map(column -> new BoardExport.Column(id(column.getColumnId()), column.getName(),
                        column.getColor(), column.getWipLimit())).toList(),
                labels ? labelService.findByBoardId(boardId).stream()
                        .map(label -> new BoardExport.Label(id(label.getLabelId()), label.getName(), label.getColor()))
                        .toList() : null,
                priorities ? boardPriorityService.findByBoardId(boardId).stream()
                        .map(level -> new BoardExport.Priority(id(level.getPriorityId()), level.getName(), level.getColor()))
                        .toList() : null,
                exportedTasks,
                parts.contains(BoardExport.PERMISSIONS) ? rules(serverId, boardId, roleNames) : null);
    }

    /** Comments that were not deleted, oldest first, with their authors' names. */
    private Map<Long, List<BoardExport.Comment>> comments(Long boardId) {
        Map<Long, List<BoardExport.Comment>> byTask = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT c.comment_id, c.task_id, c.reply_to, c.user_id, c.content, c.created_at,
                       coalesce(u.global_name, u.username) AS author
                FROM task_comments c JOIN tasks t ON t.task_id = c.task_id LEFT JOIN users u ON u.user_id = c.user_id
                WHERE t.board_id = ? AND c.deleted_at IS NULL
                ORDER BY c.created_at, c.comment_id
                """, row -> {
            Long replyTo = row.getObject("reply_to", Long.class);
            Timestamp created = row.getTimestamp("created_at");
            byTask.computeIfAbsent(row.getLong("task_id"), task -> new ArrayList<>()).add(new BoardExport.Comment(
                    id(row.getLong("comment_id")),
                    id(replyTo),
                    new BoardExport.Person(id(row.getLong("user_id")), row.getString("author")),
                    row.getString("content"),
                    created == null ? null : time(created.toLocalDateTime())));
        }, boardId);
        return byTask;
    }

    /** The board's own rules (not the server's it inherits), highest priority first. */
    private List<BoardExport.Rule> rules(Long serverId, Long boardId, Map<Long, String> roleNames) {
        List<Permission> rules = permissionService.findByScope("BOARD", boardId).stream()
                .filter(rule -> !Boolean.TRUE.equals(rule.getIsImmutable()))
                .sorted(Comparator.comparing(Permission::getPriority, Comparator.reverseOrder()).thenComparing(Permission::getId))
                .toList();
        Map<Long, String> userNames = userNames(rules);
        Function<Permission, String> subjectName = rule -> switch (rule.getSubjectType()) {
            case "ROLE" -> roleNames.get(rule.getSubjectId());
            case "USER" -> userNames.get(rule.getSubjectId());
            default -> null;
        };
        return rules.stream().map(rule -> new BoardExport.Rule(
                rule.getSubjectType(),
                id(rule.getSubjectId()),
                subjectName.apply(rule),
                rule.getKanbanPermission().getKey(),
                rule.getState(),
                rule.getPriority())).toList();
    }

    private Map<Long, String> userNames(List<Permission> rules) {
        Map<Long, String> names = new LinkedHashMap<>();
        rules.stream().filter(rule -> "USER".equals(rule.getSubjectType())).forEach(rule -> jdbcTemplate.query(
                "SELECT coalesce(global_name, username) AS name FROM users WHERE user_id = ?",
                row -> {
                    names.put(rule.getSubjectId(), row.getString("name"));
                }, rule.getSubjectId()));
        return names;
    }

    private static BoardExport.Person person(User user) {
        return new BoardExport.Person(id(user.getUserId()),
                user.getGlobalName() != null ? user.getGlobalName() : user.getUsername());
    }

    private static String id(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    static String time(LocalDateTime time) {
        return time == null ? null : time.format(TIME);
    }
}
