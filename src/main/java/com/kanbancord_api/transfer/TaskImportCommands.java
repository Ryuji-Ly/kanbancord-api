package com.kanbancord_api.transfer;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.board.BoardColumnRepository;
import com.kanbancord_api.board.BoardColumnService;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import com.kanbancord_api.label.Label;
import com.kanbancord_api.label.LabelService;
import com.kanbancord_api.label.TaskLabel;
import com.kanbancord_api.label.TaskLabelRepository;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.priority.BoardPriority;
import com.kanbancord_api.priority.BoardPriorityService;
import com.kanbancord_api.task.Task;
import com.kanbancord_api.task.TaskRepository;
import com.kanbancord_api.task.TaskService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds tasks to an existing board from text read by {@link TaskImportParser}: their columns, due
 * dates, priorities and labels matched to the board, columns it lacks created when allowed. A
 * preview and the import decide the same way, so what the preview shows is what happens.
 *
 * <p>The import is one change with one audit entry, so a whole list arriving is announced once, not
 * once per task. Like a task created by hand, it needs CREATE_TASK; new columns need CREATE_COLUMN
 * and labels APPLY_LABEL_TO_TASK, and without them the import still goes ahead without that part.
 */
@Service
@Transactional
public class TaskImportCommands {

    static final int MAX_TASKS = 500;
    static final int MAX_NEW_COLUMNS = 20;
    private static final int TITLE_MAX = 200;
    private static final int COLUMN_NAME_MAX = 50;
    private static final int EXAMPLES = 3;
    private static final Pattern RANK = Pattern.compile("^[pP]?(\\d{1,2})$");

    private final Authorizer authorizer;
    private final PermissionEvaluationService permissions;
    private final ResourceValidator resourceValidator;
    private final BoardColumnService columnService;
    private final BoardColumnRepository columnRepository;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final LabelService labelService;
    private final TaskLabelRepository taskLabelRepository;
    private final BoardPriorityService priorityService;
    private final ServerFeatureService features;
    private final UserService userService;
    private final ApplicationEventPublisher events;

    public TaskImportCommands(
            Authorizer authorizer,
            PermissionEvaluationService permissions,
            ResourceValidator resourceValidator,
            BoardColumnService columnService,
            BoardColumnRepository columnRepository,
            TaskService taskService,
            TaskRepository taskRepository,
            LabelService labelService,
            TaskLabelRepository taskLabelRepository,
            BoardPriorityService priorityService,
            ServerFeatureService features,
            UserService userService,
            ApplicationEventPublisher events) {
        this.authorizer = authorizer;
        this.permissions = permissions;
        this.resourceValidator = resourceValidator;
        this.columnService = columnService;
        this.columnRepository = columnRepository;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.labelService = labelService;
        this.taskLabelRepository = taskLabelRepository;
        this.priorityService = priorityService;
        this.features = features;
        this.userService = userService;
        this.events = events;
    }

    /** What importing the text would do, changing nothing. */
    @Transactional(readOnly = true)
    public TaskImportPlan preview(Long serverId, Long boardId, Long actorUserId, TaskImportRequest request) {
        return resolve(serverId, boardId, actorUserId, request).plan();
    }

    /** Imports the text's tasks, all or none; returns what was done, as a preview would have shown it. */
    public TaskImportPlan importTasks(Long serverId, Long boardId, Long actorUserId, TaskImportRequest request) {
        Resolved resolved = resolve(serverId, boardId, actorUserId, request);
        if (resolved.tasks.isEmpty()) {
            throw new BadRequestException("There are no tasks in it to import");
        }
        User actor = userService.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", actorUserId));

        Map<String, BoardColumn> created = new LinkedHashMap<>();
        BigDecimal columnPosition = nextColumnPosition(resolved.columns);
        for (String name : resolved.newColumns) {
            BoardColumn column = new BoardColumn();
            column.setBoard(resolved.board);
            column.setName(name);
            column.setPosition(columnPosition);
            columnPosition = columnPosition.add(BigDecimal.ONE);
            created.put(key(name), columnRepository.save(column));
        }

        Map<Long, BigDecimal> positions = new HashMap<>();
        List<Long> taskIds = new ArrayList<>();
        for (ResolvedTask item : resolved.tasks) {
            BoardColumn column = item.target.column() != null ? item.target.column() : created.get(key(item.target.name()));
            BigDecimal position = positions.computeIfAbsent(column.getColumnId(), this::endOf);
            positions.put(column.getColumnId(), position.add(BigDecimal.ONE));

            Task task = new Task();
            task.setBoard(resolved.board);
            task.setColumn(column);
            task.setTitle(item.title);
            task.setDescription(item.description);
            task.setPosition(position);
            task.setDueDate(item.due);
            task.setPriorityId(item.priority == null ? null : item.priority.getPriorityId());
            task.setIsArchived(false);
            task.setCreatedBy(actor);
            task = taskRepository.save(task);
            taskIds.add(task.getTaskId());
            for (Label label : item.labels) {
                TaskLabel link = new TaskLabel();
                link.setTask(task);
                link.setLabel(label);
                taskLabelRepository.save(link);
            }
        }

        TaskImportPlan plan = resolved.plan();
        Map<String, Object> imported = new LinkedHashMap<>();
        imported.put("name", resolved.board.getName());
        imported.put("count", taskIds.size());
        imported.put("columns", plan.columns().stream().map(TaskImportPlan.Column::name).toList());
        imported.put("createdColumns", resolved.newColumns);
        imported.put("taskIds", taskIds);
        events.publishEvent(DomainEvent.created(EventType.TASKS_IMPORTED, serverId, boardId, boardId, actorUserId, imported));
        return plan;
    }

    // ── Deciding where everything goes ───────────────────────────────────────

    /** Where a task goes: one of the board's columns, or a column the import creates. */
    private record Target(BoardColumn column, String name) {
    }

    private record ResolvedTask(String title, String description, Target target, LocalDateTime due,
                                BoardPriority priority, List<Label> labels) {
    }

    private static final class Notes {
        private final Map<String, int[]> counts = new LinkedHashMap<>();
        private final Map<String, Set<String>> examples = new LinkedHashMap<>();

        void add(String code, String example) {
            counts.computeIfAbsent(code, ignored -> new int[1])[0]++;
            Set<String> list = examples.computeIfAbsent(code, ignored -> new LinkedHashSet<>());
            if (example != null && list.size() < EXAMPLES) {
                list.add(example);
            }
        }

        void add(String code, int count) {
            counts.computeIfAbsent(code, ignored -> new int[1])[0] += count;
        }

        List<TaskImportPlan.Note> list() {
            return counts.entrySet().stream()
                    .map(entry -> new TaskImportPlan.Note(entry.getKey(), entry.getValue()[0],
                            examples.getOrDefault(entry.getKey(), Set.of()).isEmpty()
                                    ? null : List.copyOf(examples.get(entry.getKey()))))
                    .toList();
        }
    }

    private static final class Resolved {
        Board board;
        TaskImportParser.Parsed parsed;
        List<BoardColumn> columns;
        final List<String> newColumns = new ArrayList<>();
        final List<ResolvedTask> tasks = new ArrayList<>();
        final Notes notes = new Notes();

        TaskImportPlan plan() {
            Map<String, Integer> counts = new HashMap<>();
            for (ResolvedTask task : tasks) {
                counts.merge(key(task.target.name()), 1, Integer::sum);
            }
            List<TaskImportPlan.Column> planned = new ArrayList<>();
            for (BoardColumn column : columns) {
                Integer count = counts.get(key(column.getName()));
                if (count != null) {
                    planned.add(new TaskImportPlan.Column(String.valueOf(column.getColumnId()), column.getName(), false, count));
                }
            }
            for (String name : newColumns) {
                planned.add(new TaskImportPlan.Column(null, name, true, counts.getOrDefault(key(name), 0)));
            }
            List<TaskImportPlan.Task> planTasks = tasks.stream().map(task -> new TaskImportPlan.Task(task.title, task.description,
                    task.target.name(), task.due == null ? null : task.due.toString(),
                    task.priority == null ? null : task.priority.getName(),
                    task.labels.isEmpty() ? null : task.labels.stream().map(Label::getName).toList())).toList();
            return new TaskImportPlan(parsed.format(), parsed.headers(), parsed.fields(), planned, planTasks, notes.list());
        }
    }

    private Resolved resolve(Long serverId, Long boardId, Long actorUserId, TaskImportRequest request) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_TASK");
        resourceValidator.validateBoardNotArchived(board);

        Resolved resolved = new Resolved();
        resolved.board = board;
        resolved.parsed = TaskImportParser.parse(request.text(), formatOf(request.format()), request.fields());
        resolved.columns = columnService.findByBoardIdOrdered(boardId);
        if (resolved.columns.isEmpty()) {
            throw new BadRequestException("This board has no columns to add tasks to yet");
        }

        List<BoardColumn> columns = resolved.columns;
        BoardColumn fallback = columns.stream()
                .filter(column -> column.getColumnId().equals(request.defaultColumnId()))
                .findFirst().orElse(columns.get(0));
        BoardColumn last = columns.get(columns.size() - 1);
        Map<String, BoardColumn> byName = new HashMap<>();
        columns.forEach(column -> byName.putIfAbsent(key(column.getName()), column));

        boolean wantColumns = !Boolean.FALSE.equals(request.createColumns());
        boolean mayCreateColumns = wantColumns && permissions.isAllowed(serverId, boardId, actorUserId, "CREATE_COLUMN");
        boolean dueOn = features.isEnabled(serverId, boardId, Feature.DUE_DATES);
        boolean prioritiesOn = features.isEnabled(serverId, boardId, Feature.PRIORITIES);
        boolean labelsOn = features.isEnabled(serverId, boardId, Feature.LABELS);
        boolean mayLabel = labelsOn && permissions.isAllowed(serverId, boardId, actorUserId, "APPLY_LABEL_TO_TASK");
        List<BoardPriority> levels = prioritiesOn ? priorityService.findByBoardId(boardId) : List.of();
        Map<String, Label> labels = new HashMap<>();
        if (labelsOn) {
            labelService.findByBoardId(boardId).forEach(label -> labels.putIfAbsent(key(label.getName()), label));
        }

        List<TaskImportParser.Item> items = resolved.parsed.items();
        if (items.size() > MAX_TASKS) {
            resolved.notes.add(TaskImportPlan.TOO_MANY, items.size() - MAX_TASKS);
            items = items.subList(0, MAX_TASKS);
        }
        Notes notes = resolved.notes;
        for (TaskImportParser.Item item : items) {
            String title = item.title().strip();
            String description = item.description();
            if (title.length() > TITLE_MAX) {
                description = description == null ? title : title + "\n\n" + description;
                title = title.substring(0, TITLE_MAX - 1).strip() + "…";
                notes.add(TaskImportPlan.TITLE_SHORTENED, null);
            }

            Target target = new Target(fallback, fallback.getName());
            if (item.done()) {
                target = new Target(last, last.getName());
            } else if (item.column() != null && !item.column().isBlank()) {
                String name = shorten(item.column().strip(), COLUMN_NAME_MAX);
                BoardColumn existing = byName.get(key(name));
                if (existing != null) {
                    target = new Target(existing, existing.getName());
                } else if (!mayCreateColumns) {
                    notes.add(TaskImportPlan.COLUMNS_NOT_CREATED, name);
                } else {
                    Optional<String> planned = resolved.newColumns.stream().filter(n -> key(n).equals(key(name))).findFirst();
                    if (planned.isPresent()) {
                        target = new Target(null, planned.get());
                    } else if (resolved.newColumns.size() < MAX_NEW_COLUMNS) {
                        resolved.newColumns.add(name);
                        target = new Target(null, name);
                    } else {
                        notes.add(TaskImportPlan.TOO_MANY_COLUMNS, name);
                    }
                }
            }

            LocalDateTime due = null;
            if (item.due() != null && !item.due().isBlank()) {
                if (!dueOn) {
                    notes.add(TaskImportPlan.DUE_DATES_OFF, null);
                } else {
                    due = dueOf(item.due().strip());
                    if (due == null) {
                        notes.add(TaskImportPlan.DUE_UNREADABLE, item.due().strip());
                    }
                }
            }

            BoardPriority priority = null;
            if (item.priority() != null && !item.priority().isBlank()) {
                if (!prioritiesOn) {
                    notes.add(TaskImportPlan.PRIORITIES_OFF, null);
                } else {
                    priority = priorityOf(item.priority().strip(), levels);
                    if (priority == null) {
                        notes.add(TaskImportPlan.PRIORITY_UNKNOWN, item.priority().strip());
                    }
                }
            }

            List<Label> applied = new ArrayList<>();
            for (String name : item.labels()) {
                if (!labelsOn) {
                    notes.add(TaskImportPlan.LABELS_OFF, null);
                } else if (!mayLabel) {
                    notes.add(TaskImportPlan.LABELS_NOT_ALLOWED, null);
                } else {
                    Label label = labels.get(key(name));
                    if (label == null) {
                        notes.add(TaskImportPlan.LABEL_UNKNOWN, name);
                    } else if (!applied.contains(label)) {
                        applied.add(label);
                    }
                }
            }
            resolved.tasks.add(new ResolvedTask(title, description, target, due, priority, applied));
        }
        return resolved;
    }

    private static TaskImportParser.Format formatOf(String format) {
        if (format == null || format.isBlank()) {
            return TaskImportParser.Format.AUTO;
        }
        try {
            return TaskImportParser.Format.valueOf(format.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown import format: " + format);
        }
    }

    /** 2026-10-20 (due at the end of the day), 2026-10-20 18:00 or 2026-10-20T18:00:00. */
    static LocalDateTime dueOf(String text) {
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).atTime(LocalTime.of(23, 59));
            }
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** A level by its name, or by its rank: "1" or "P1" is the most urgent. */
    private static BoardPriority priorityOf(String text, List<BoardPriority> levels) {
        for (BoardPriority level : levels) {
            if (level.getName().equalsIgnoreCase(text)) {
                return level;
            }
        }
        Matcher rank = RANK.matcher(text);
        if (rank.matches()) {
            int position = Integer.parseInt(rank.group(1));
            return levels.stream().filter(level -> Objects.equals(level.getPosition(), position)).findFirst().orElse(null);
        }
        return null;
    }

    private BigDecimal endOf(Long columnId) {
        return taskService.findByColumnIdOrdered(columnId).stream()
                .map(Task::getPosition)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .map(last -> last.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE))
                .orElse(BigDecimal.ONE);
    }

    private static BigDecimal nextColumnPosition(List<BoardColumn> columns) {
        return columns.stream()
                .map(BoardColumn::getPosition)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .map(last -> last.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE))
                .orElse(BigDecimal.ONE);
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).strip();
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
