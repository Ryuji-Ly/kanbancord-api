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
 * issue to its issues column, at most {@code kanbancord.roadmap.issues-per-hour} an hour.
 */
@Service
public class RoadmapService {

    private final Long boardId;
    private final String issuesColumnName;
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
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbcTemplate;

    public RoadmapService(
            @Value("${kanbancord.roadmap.board-id:}") String boardId,
            @Value("${kanbancord.roadmap.issues-column:Issues}") String issuesColumnName,
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
            ApplicationEventPublisher events,
            JdbcTemplate jdbcTemplate) {
        this.boardId = boardId == null || boardId.isBlank() ? null : Long.valueOf(boardId.trim());
        this.issuesColumnName = issuesColumnName;
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
        this.events = events;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The board, or not found while none is configured (or it was deleted). */
    private Board board() {
        return Optional.ofNullable(boardId).flatMap(boardRepository::findById)
                .filter(board -> !Boolean.TRUE.equals(board.getIsArchived()))
                .orElseThrow(() -> new ResourceNotFoundException("Roadmap", "board", "configured"));
    }

    private Optional<BoardColumn> issuesColumn(List<BoardColumn> columns) {
        return columns.stream().filter(column -> column.getName().trim().equalsIgnoreCase(issuesColumnName)).findFirst();
    }

    @Transactional(readOnly = true)
    public RoadmapResponse roadmap() {
        Board board = board();
        Long serverId = board.getServer().getServerId();
        Set<Feature> on = features.enabled(serverId, board.getBoardId());
        List<BoardColumn> columns = columnService.findByBoardIdOrdered(board.getBoardId());

        Map<Long, RoadmapResponse.Chip> labels = on.contains(Feature.LABELS)
                ? labelService.findByBoardId(board.getBoardId()).stream().collect(Collectors.toMap(
                        label -> label.getLabelId(), label -> new RoadmapResponse.Chip(label.getName(), label.getColor())))
                : Map.of();
        Map<Long, List<RoadmapResponse.Chip>> labelsByTask = labels.isEmpty() ? Map.of()
                : taskLabelService.findByBoardId(board.getBoardId()).stream()
                        .filter(link -> labels.containsKey(link.getLabel().getLabelId()))
                        .collect(Collectors.groupingBy(link -> link.getTask().getTaskId(),
                                Collectors.mapping(link -> labels.get(link.getLabel().getLabelId()), Collectors.toList())));
        Map<Long, RoadmapResponse.Chip> priorities = on.contains(Feature.PRIORITIES)
                ? priorityService.findByBoardId(board.getBoardId()).stream().collect(Collectors.toMap(
                        level -> level.getPriorityId(), level -> new RoadmapResponse.Chip(level.getName(), level.getColor())))
                : Map.of();

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
                        task.getPriorityId() == null ? null : priorities.get(task.getPriorityId()),
                        task.getCreatedAt()))
                .toList();

        return new RoadmapResponse(
                board.getName(),
                board.getDescription(),
                issuesColumn(columns).map(column -> String.valueOf(column.getColumnId())).orElse(null),
                columns.stream().map(column -> new RoadmapResponse.Column(String.valueOf(column.getColumnId()), column.getName())).toList(),
                tasks);
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
                saved.getTitle(), saved.getDescription(), List.of(), null, saved.getCreatedAt());
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
