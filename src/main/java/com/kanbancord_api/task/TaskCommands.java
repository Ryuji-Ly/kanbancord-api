package com.kanbancord_api.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardColumn;
import com.kanbancord_api.board.BoardColumnService;
import com.kanbancord_api.common.Positions;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import com.kanbancord_api.priority.BoardPriorityService;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Creating, changing and deleting tasks: authorization, validation, the change itself and its
 * {@link DomainEvent}, all in one transaction.
 */
@Service
@Transactional
public class TaskCommands {

    private final TaskService taskService;
    private final BoardColumnService boardColumnService;
    private final UserService userService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final BoardPriorityService boardPriorityService;
    private final ServerFeatureService features;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final ObjectMapper objectMapper;

    public TaskCommands(
            TaskService taskService,
            BoardColumnService boardColumnService,
            UserService userService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            BoardPriorityService boardPriorityService,
            ServerFeatureService features,
            TaskAssignmentRepository taskAssignmentRepository,
            ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.boardColumnService = boardColumnService;
        this.userService = userService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.boardPriorityService = boardPriorityService;
        this.features = features;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.objectMapper = objectMapper;
    }

    public TaskResponse create(Long serverId, Long boardId, Long actorUserId, TaskRequest submitted) {
        TaskRequest request = keepSwitchedOffFields(serverId, boardId, null, submitted);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_TASK");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.boardId());

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        BoardColumn column = boardColumnService.findById(request.columnId())
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", request.columnId()));
        resourceValidator.validateColumnBelongsToBoard(request.columnId(), boardId);

        Task task = new Task();
        task.setBoard(board);
        task.setColumn(column);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setPosition(request.position() != null ? request.position() : endOf(column.getColumnId()));
        task.setPriorityId(priorityIn(boardId, request.priorityId()));
        task.setDueDate(request.dueDate());
        task.setMetadata(request.metadata());
        task.setIsArchived(false);
        // The creator is always the actor; request.createdBy is ignored.
        task.setCreatedBy(requireUser(actorUserId));

        TaskResponse created = TaskResponse.from(taskService.create(task));
        events.publishEvent(DomainEvent.created(EventType.TASK_CREATED, serverId, boardId, created.taskId(),
                actorUserId, created));
        return created;
    }

    /**
     * Requires only the permissions for what actually changes: moving a task must not need
     * EDIT_TASK, and editing a task in place must not need MOVE_TASK. A request that changes
     * nothing needs VIEW_TASK and writes nothing.
     */
    public TaskResponse update(Long serverId, Long boardId, Long taskId, Long actorUserId, TaskRequest submitted) {
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, submitted.boardId());
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        TaskRequest request = keepSwitchedOffFields(serverId, boardId, task, submitted);
        boolean contentChanged = contentChanged(task, request);
        boolean moved = moved(task, request);
        if (!contentChanged && !moved) {
            authorizer.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_TASK");
            return TaskResponse.from(task);
        }
        if (contentChanged) {
            authorizer.requireBoardPermission(actorUserId, serverId, boardId, "EDIT_TASK");
        }
        if (moved) {
            authorizer.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_TASK");
        }
        resourceValidator.validateTaskNotArchived(task);

        TaskResponse before = TaskResponse.from(task);
        task.setTitle(request.title());
        task.setDescription(request.description());
        if (request.position() != null) {
            task.setPosition(request.position());
        }
        task.setPriorityId(priorityIn(boardId, request.priorityId()));
        // Like the priority, the due date is part of every edit: none means the task has none. Clients
        // send the whole task; a due date hidden by a switched-off feature was kept above.
        task.setDueDate(request.dueDate());
        if (request.metadata() != null) {
            task.setMetadata(request.metadata());
        }
        if (request.columnId() != null) {
            BoardColumn column = boardColumnService.findById(request.columnId())
                    .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", request.columnId()));
            resourceValidator.validateTaskMove(task, request.columnId());
            task.setColumn(column);
        }

        TaskResponse after = TaskResponse.from(taskService.update(task));
        events.publishEvent(DomainEvent.changed(EventType.TASK_UPDATED, serverId, boardId, taskId, actorUserId,
                before, after));
        return after;
    }

    /**
     * Puts a task at a position in a column (its own or another), so a drag and drop is one request,
     * one event and one audit entry. Usually only the moved task is written, placed between its new
     * neighbours; the column is renumbered only when there is no room left between them. Needs only
     * MOVE_TASK.
     */
    public TaskResponse move(Long serverId, Long boardId, Long taskId, Long actorUserId, TaskMoveRequest request) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);
        resourceValidator.validateTaskMove(task, request.columnId());
        BoardColumn target = boardColumnService.findById(request.columnId())
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", request.columnId()));

        TaskResponse before = TaskResponse.from(task);
        boolean sameColumn = task.getColumn().getColumnId().equals(target.getColumnId());

        List<Task> column = taskService.findByColumnIdOrdered(target.getColumnId());
        int currentIndex = sameColumn ? indexOf(column, taskId) : -1;
        List<Task> others = new ArrayList<>(column);
        others.removeIf(other -> other.getTaskId().equals(taskId));
        int index = Math.min(request.index(), others.size());
        if (sameColumn && index == currentIndex) {
            return before;
        }

        // Halfway between the new neighbours, unless they are too close (or unnumbered): then the
        // column is numbered afresh, the only case where other tasks are written.
        BigDecimal previous = index > 0 ? others.get(index - 1).getPosition() : null;
        BigDecimal next = index < others.size() ? others.get(index).getPosition() : null;
        boolean unnumbered = (index > 0 && previous == null) || (index < others.size() && next == null);
        Optional<BigDecimal> position = unnumbered ? Optional.empty() : Positions.between(previous, next);
        task.setColumn(target);
        if (position.isPresent()) {
            task.setPosition(position.get());
            taskService.updateAll(List.of(task));
        } else {
            Positions.insert(others, index, task);
            Positions.renumber(others, Task::getPosition, Task::setPosition);
            taskService.updateAll(others);
        }
        TaskResponse after = TaskResponse.from(task);
        boolean moved = !Objects.equals(before.columnId(), after.columnId())
                || before.position() == null
                || before.position().compareTo(after.position()) != 0;
        if (moved) {
            events.publishEvent(DomainEvent.changed(EventType.TASK_MOVED, serverId, boardId, taskId, actorUserId,
                    before, after));
        }
        return after;
    }

    /** The position after the last task of a column, so tasks created without one go to the end. */
    private BigDecimal endOf(Long columnId) {
        return taskService.findByColumnIdOrdered(columnId).stream()
                .map(Task::getPosition)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .map(last -> last.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE))
                .orElse(BigDecimal.ONE);
    }

    private static int indexOf(List<Task> tasks, Long taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getTaskId().equals(taskId)) {
                return i;
            }
        }
        return -1;
    }

    public void delete(Long serverId, Long boardId, Long taskId, Long actorUserId) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "DELETE_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        // The task's assignments go with it; they are kept in its audit entry, so the people who were
        // assigned can still be told it was deleted.
        Map<String, Object> before = objectMapper.convertValue(TaskResponse.from(task), new TypeReference<>() {
        });
        before.put("_assigneeIds", taskAssignmentRepository.findByTask_TaskId(task.getTaskId()).stream()
                .map(assignment -> String.valueOf(assignment.getUser().getUserId()))
                .toList());
        taskService.deleteById(task.getTaskId());
        events.publishEvent(DomainEvent.deleted(EventType.TASK_DELETED, serverId, boardId, taskId, actorUserId,
                before));
    }

    /**
     * Fields of a feature the server or board has switched off keep what the task has (nothing, for
     * a new task), so saving a task where they are hidden never clears them.
     */
    private TaskRequest keepSwitchedOffFields(Long serverId, Long boardId, Task task, TaskRequest request) {
        Long priorityId = features.isEnabled(serverId, boardId, Feature.PRIORITIES)
                ? request.priorityId()
                : task == null ? null : task.getPriorityId();
        java.time.LocalDateTime dueDate = features.isEnabled(serverId, boardId, Feature.DUE_DATES)
                ? request.dueDate()
                : task == null ? null : task.getDueDate();
        return request.withPriorityAndDue(priorityId, dueDate);
    }

    /** The priority level, checked to belong to the board; null for none. */
    private Long priorityIn(Long boardId, Long priorityId) {
        return priorityId == null ? null : boardPriorityService.requireInBoard(priorityId, boardId).getPriorityId();
    }

    private User requireUser(Long userId) {
        return userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    }

    private static boolean contentChanged(Task task, TaskRequest request) {
        return !Objects.equals(task.getTitle(), request.title())
                || !Objects.equals(task.getDescription(), request.description())
                || !Objects.equals(task.getPriorityId(), request.priorityId())
                || !Objects.equals(task.getDueDate(), request.dueDate())
                || (request.metadata() != null && !request.metadata().equals(task.getMetadata()));
    }

    private static boolean moved(Task task, TaskRequest request) {
        boolean columnChanged = request.columnId() != null
                && !request.columnId().equals(task.getColumn().getColumnId());
        boolean positionChanged = request.position() != null
                && (task.getPosition() == null || request.position().compareTo(task.getPosition()) != 0);
        return columnChanged || positionChanged;
    }
}
