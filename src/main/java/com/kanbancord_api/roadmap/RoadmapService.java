package com.kanbancord_api.roadmap;

import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.board.BoardColumnService;
import com.kanbancord_api.board.BoardRepository;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.exception.TooManyRequestsException;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import com.kanbancord_api.label.LabelService;
import com.kanbancord_api.label.TaskLabelService;
import com.kanbancord_api.priority.BoardPriorityService;
import com.kanbancord_api.task.Task;
import com.kanbancord_api.task.TaskComment;
import com.kanbancord_api.task.TaskCommentResponse;
import com.kanbancord_api.task.TaskCommentService;
import com.kanbancord_api.task.TaskRepository;
import com.kanbancord_api.task.TaskResponse;
import com.kanbancord_api.task.TaskService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The roadmap: KanbanCord's own development board, shown to everyone on the website. Which board it
 * is comes from configuration ({@code KANBANCORD_ROADMAP_BOARD_ID}), so it is a single exception for
 * the developer's board, not a setting servers have. Anyone may read it; anyone signed in may add an
 * issue to its first column, whatever it is called, at most {@code kanbancord.roadmap.issues-per-hour} an hour.
 * With comments on for the board, anyone may read a task's comments and anyone signed in may comment, at most
 * {@link #COMMENTS_PER_HOUR} an hour; comments show the writer's Discord name.
 */
@Service
public class RoadmapService {

    static final int COMMENTS_PER_HOUR = 10;

    private final Long boardId;
    private final int issuesPerHour;
    private final BoardRepository boardRepository;
    private final BoardColumnService columnService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final LabelService labelService;
    private final TaskLabelService taskLabelService;
    private final BoardPriorityService priorityService;
    private final ServerFeatureService features;
    private final UserService userService;
    private final TaskCommentService commentService;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbcTemplate;

    public RoadmapService(
            @Value("${kanbancord.roadmap.board-id:}") String boardId,
            @Value("${kanbancord.roadmap.issues-per-hour:1}") int issuesPerHour,
            BoardRepository boardRepository,
            BoardColumnService columnService,
            TaskService taskService,
            TaskRepository taskRepository,
            LabelService labelService,
            TaskLabelService taskLabelService,
            BoardPriorityService priorityService,
            ServerFeatureService features,
            UserService userService,
            TaskCommentService commentService,
            ApplicationEventPublisher events,
            JdbcTemplate jdbcTemplate) {
        this.boardId = boardId == null || boardId.isBlank() ? null : Long.valueOf(boardId.trim());
        this.issuesPerHour = issuesPerHour;
        this.boardRepository = boardRepository;
        this.columnService = columnService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.labelService = labelService;
        this.taskLabelService = taskLabelService;
        this.priorityService = priorityService;
        this.features = features;
        this.userService = userService;
        this.commentService = commentService;
        this.events = events;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The board, or not found while none is configured (or it was deleted). */
    private Board board() {
        return Optional.ofNullable(boardId).flatMap(boardRepository::findById)
                .filter(board -> !Boolean.TRUE.equals(board.getIsArchived()))
                .orElseThrow(() -> new ResourceNotFoundException("Roadmap", "board", "configured"));
    }

    /** Issues go to the board's first column, so it can be renamed freely. */
    private Optional<BoardColumn> issuesColumn(List<BoardColumn> columns) {
        return columns.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public RoadmapResponse roadmap() {
        Board board = board();
        Long serverId = board.getServer().getServerId();
        Set<Feature> on = features.enabled(serverId, board.getBoardId());
        List<BoardColumn> columns = columnService.findByBoardIdOrdered(board.getBoardId());

        List<RoadmapResponse.Label> labels = on.contains(Feature.LABELS)
                ? labelService.findByBoardId(board.getBoardId()).stream()
                        .map(label -> new RoadmapResponse.Label(String.valueOf(label.getLabelId()), label.getName(), label.getColor()))
                        .toList()
                : List.of();
        Map<Long, List<String>> labelsByTask = labels.isEmpty() ? Map.of()
                : taskLabelService.findByBoardId(board.getBoardId()).stream().collect(Collectors.groupingBy(
                        link -> link.getTask().getTaskId(),
                        Collectors.mapping(link -> String.valueOf(link.getLabel().getLabelId()), Collectors.toList())));
        List<RoadmapResponse.Priority> priorities = on.contains(Feature.PRIORITIES)
                ? priorityService.findByBoardId(board.getBoardId()).stream()
                        .map(level -> new RoadmapResponse.Priority(String.valueOf(level.getPriorityId()), level.getName(),
                                level.getColor(), level.getPosition()))
                        .toList()
                : List.of();
        boolean showPriorities = !priorities.isEmpty();
        boolean commentsOn = on.contains(Feature.COMMENTS);
        Map<Long, Integer> commentCounts = new java.util.HashMap<>();
        if (commentsOn) {
            jdbcTemplate.query("""
                    SELECT c.task_id, COUNT(*) AS comments FROM task_comments c JOIN tasks t ON t.task_id = c.task_id
                    WHERE t.board_id = ? AND c.deleted_at IS NULL GROUP BY c.task_id
                    """, rs -> {
                commentCounts.put(rs.getLong("task_id"), rs.getInt("comments"));
            }, board.getBoardId());
        }

        Map<Long, Integer> order = columns.stream().collect(Collectors.toMap(BoardColumn::getColumnId, columns::indexOf));
        List<RoadmapResponse.Task> tasks = taskService.findByBoardId(board.getBoardId()).stream()
                .filter(task -> !Boolean.TRUE.equals(task.getIsArchived()))
                .sorted(Comparator.<Task, Integer>comparing(task -> order.getOrDefault(task.getColumn().getColumnId(), 0))
                        .thenComparing(Task::getPosition, Comparator.nullsLast(BigDecimal::compareTo))
                        .thenComparing(Task::getTaskId))
                .map(task -> new RoadmapResponse.Task(
                        String.valueOf(task.getTaskId()),
                        String.valueOf(task.getColumn().getColumnId()),
                        task.getTitle(),
                        task.getDescription(),
                        labelsByTask.getOrDefault(task.getTaskId(), List.of()),
                        showPriorities && task.getPriorityId() != null ? String.valueOf(task.getPriorityId()) : null,
                        task.getCreatedAt(),
                        commentCounts.getOrDefault(task.getTaskId(), 0)))
                .toList();

        return new RoadmapResponse(
                board.getName(),
                board.getDescription(),
                issuesColumn(columns).map(column -> String.valueOf(column.getColumnId())).orElse(null),
                columns.stream().map(column -> new RoadmapResponse.Column(String.valueOf(column.getColumnId()), column.getName(),
                        column.getColor())).toList(),
                labels,
                priorities,
                tasks,
                commentsOn);
    }

    /** Whether this is the roadmap's board. */
    public boolean isRoadmapBoard(Long boardId) {
        return this.boardId != null && this.boardId.equals(boardId);
    }

    /** A task's comments, oldest first; only for tasks on the roadmap, with comments on. */
    @Transactional(readOnly = true)
    public List<RoadmapResponse.Comment> comments(Long taskId) {
        roadmapTask(taskId);
        return commentService.findActiveByTaskId(taskId).stream()
                .sorted(Comparator.comparing(TaskComment::getCreatedAt).thenComparing(TaskComment::getCommentId))
                .map(RoadmapService::comment)
                .toList();
    }

    /**
     * A comment from someone signed in, recorded and announced like any other, so the board's feeds see it and
     * the people taking part in the issue hear about it (see RoadmapReplies).
     */
    @Transactional
    public RoadmapResponse.Comment addComment(Long userId, Long taskId, String content) {
        Task task = roadmapTask(taskId);
        User user = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
        List<LocalDateTime> recent = jdbcTemplate.queryForList("""
                SELECT c.created_at FROM task_comments c JOIN tasks t ON t.task_id = c.task_id
                WHERE t.board_id = ? AND c.user_id = ? AND c.created_at > ? ORDER BY c.created_at
                """, LocalDateTime.class, task.getBoard().getBoardId(), userId, LocalDateTime.now().minusHours(1));
        if (recent.size() >= COMMENTS_PER_HOUR) {
            Duration wait = Duration.between(LocalDateTime.now(), recent.get(0).plusHours(1));
            throw new TooManyRequestsException("You can comment again in " + Math.max(1, wait.toMinutes() + 1)
                    + " minutes", Math.max(1, wait.getSeconds()));
        }

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(user);
        comment.setContent(content.trim());
        TaskCommentResponse created = TaskCommentResponse.from(commentService.create(comment));
        events.publishEvent(DomainEvent.created(EventType.TASK_COMMENT_CREATED, task.getBoard().getServer().getServerId(),
                task.getBoard().getBoardId(), created.commentId(), userId, created));
        return new RoadmapResponse.Comment(String.valueOf(created.commentId()), nameOf(user), created.content(),
                created.createdAt(), false);
    }

    /** A task on the roadmap that is not archived, while comments are on; not found otherwise. */
    private Task roadmapTask(Long taskId) {
        Board board = board();
        if (!features.enabled(board.getServer().getServerId(), board.getBoardId()).contains(Feature.COMMENTS)) {
            throw new ResourceNotFoundException("Roadmap comments", "board", board.getBoardId());
        }
        return taskRepository.findById(taskId)
                .filter(task -> task.getBoard().getBoardId().equals(board.getBoardId()))
                .filter(task -> !Boolean.TRUE.equals(task.getIsArchived()))
                .orElseThrow(() -> new ResourceNotFoundException("Roadmap task", "taskId", taskId));
    }

    private static RoadmapResponse.Comment comment(TaskComment comment) {
        return new RoadmapResponse.Comment(String.valueOf(comment.getCommentId()), nameOf(comment.getUser()),
                comment.getContent(), comment.getCreatedAt(), !comment.getEdits().isEmpty());
    }

    private static String nameOf(User user) {
        String display = user.getGlobalName();
        return display == null || display.isBlank() ? user.getUsername() : display.trim();
    }

    /**
     * Adds an issue from someone signed in, at the end of the issues column, as their own task. It is
     * recorded and announced like any other new task, so the board's feeds see it.
     */
    @Transactional
    public RoadmapResponse.Task addIssue(Long userId, String title, String description) {
        Board board = board();
        BoardColumn column = issuesColumn(columnService.findByBoardIdOrdered(board.getBoardId()))
                .orElseThrow(() -> new BadRequestException("The roadmap has no column for issues"));
        User user = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<LocalDateTime> recent = jdbcTemplate.queryForList(
                "SELECT created_at FROM tasks WHERE board_id = ? AND created_by = ? AND created_at > ? ORDER BY created_at",
                LocalDateTime.class, board.getBoardId(), userId, since);
        if (recent.size() >= issuesPerHour) {
            Duration wait = Duration.between(LocalDateTime.now(), recent.get(0).plusHours(1));
            throw new TooManyRequestsException("You can add another issue in " + Math.max(1, wait.toMinutes() + 1)
                    + " minutes", Math.max(1, wait.getSeconds()));
        }

        Task task = new Task();
        task.setBoard(board);
        task.setColumn(column);
        task.setTitle(title.trim());
        task.setDescription(description == null || description.isBlank() ? null : description.trim());
        task.setPosition(endOf(column.getColumnId()));
        task.setIsArchived(false);
        task.setCreatedBy(user);
        Task saved = taskService.create(task);

        Long serverId = board.getServer().getServerId();
        events.publishEvent(DomainEvent.created(EventType.TASK_CREATED, serverId, board.getBoardId(), saved.getTaskId(),
                userId, TaskResponse.from(saved)));
        return new RoadmapResponse.Task(String.valueOf(saved.getTaskId()), String.valueOf(column.getColumnId()),
                saved.getTitle(), saved.getDescription(), List.of(), null, saved.getCreatedAt(), 0);
    }

    private BigDecimal endOf(Long columnId) {
        return taskService.findByColumnIdOrdered(columnId).stream()
                .map(Task::getPosition)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .map(last -> last.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE))
                .orElse(BigDecimal.ONE);
    }
}
