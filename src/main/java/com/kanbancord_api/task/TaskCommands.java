package com.kanbancord_api.task;

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
import java.util.Objects;

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

    public TaskCommands(
            TaskService taskService,
            BoardColumnService boardColumnService,
            UserService userService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            BoardPriorityService boardPriorityService,
            ServerFeatureService features) {
        this.taskService = taskService;
        this.boardColumnService = boardColumnService;
        this.userService = userService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.boardPriorityService = boardPriorityService;
        this.features = features;
    }

    public TaskResponse create(Long serverId, Long boardId, Long actorUserId, TaskRequest request) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_TASK");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        BoardColumn column = boardColumnService.findById(request.getColumnId())
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", request.getColumnId()));
        resourceValidator.validateColumnBelongsToBoard(request.getColumnId(), boardId);

        Task task = new Task();
        task.setBoard(board);
        task.setColumn(column);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setPosition(request.getPosition() != null ? request.getPosition() : endOf(column.getColumnId()));
        keepSwitchedOffFields(serverId, null, request);
        task.setPriorityId(priorityIn(boardId, request.getPriorityId()));
        task.setDueDate(request.getDueDate());
        task.setMetadata(request.getMetadata());
        task.setIsArchived(false);
        // The creator is always the actor; request.createdBy is ignored.
        task.setCreatedBy(requireUser(actorUserId));

        TaskResponse created = TaskResponse.from(taskService.create(task));
        events.publishEvent(DomainEvent.created(EventType.TASK_CREATED, serverId, boardId, created.getTaskId(),
                actorUserId, created));
        return created;
    }

    /**
     * Requires only the permissions for what actually changes: moving a task must not need
     * EDIT_TASK, and editing a task in place must not need MOVE_TASK. A request that changes
     * nothing needs VIEW_TASK and writes nothing.
     */
    public TaskResponse update(Long serverId, Long boardId, Long taskId, Long actorUserId, TaskRequest request) {
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        keepSwitchedOffFields(serverId, task, request);
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
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        if (request.getPosition() != null) {
            task.setPosition(request.getPosition());
        }
        task.setPriorityId(priorityIn(boardId, request.getPriorityId()));
        if (request.getDueDate() != null) {
            task.setDueDate(request.getDueDate());
        }
        if (request.getMetadata() != null) {
            task.setMetadata(request.getMetadata());
        }
        if (request.getColumnId() != null) {
            BoardColumn column = boardColumnService.findById(request.getColumnId())
                    .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", request.getColumnId()));
            resourceValidator.validateTaskMove(task, request.getColumnId());
            task.setColumn(column);
        }

        TaskResponse after = TaskResponse.from(taskService.update(task));
        events.publishEvent(DomainEvent.changed(EventType.TASK_UPDATED, serverId, boardId, taskId, actorUserId,
                before, after));
        return after;
    }

    /**
     * Puts a task at a position in a column (its own or another) and renumbers the affected columns,
     * so a drag and drop is one request, one event and one audit entry. Needs only MOVE_TASK.
     */
    public TaskResponse move(Long serverId, Long boardId, Long taskId, Long actorUserId, TaskMoveRequest request) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);
        resourceValidator.validateTaskMove(task, request.getColumnId());
        BoardColumn target = boardColumnService.findById(request.getColumnId())
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", request.getColumnId()));

        TaskResponse before = TaskResponse.from(task);
        Long sourceColumnId = task.getColumn().getColumnId();
        List<Task> changed = new ArrayList<>();

        List<Task> targetTasks = columnTasksWithout(target.getColumnId(), taskId);
        Positions.insert(targetTasks, request.getIndex(), task);
        task.setColumn(target);
        Positions.renumber(targetTasks, Task::getPosition, Task::setPosition);
        changed.addAll(targetTasks);

        if (!sourceColumnId.equals(target.getColumnId())) {
            List<Task> sourceTasks = columnTasksWithout(sourceColumnId, taskId);
            Positions.renumber(sourceTasks, Task::getPosition, Task::setPosition);
            changed.addAll(sourceTasks);
        }

        taskService.updateAll(changed);
        TaskResponse after = TaskResponse.from(task);
        boolean moved = !Objects.equals(before.getColumnId(), after.getColumnId())
                || before.getPosition() == null
                || before.getPosition().compareTo(after.getPosition()) != 0;
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

    private List<Task> columnTasksWithout(Long columnId, Long taskId) {
        List<Task> tasks = new ArrayList<>(taskService.findByColumnIdOrdered(columnId));
        tasks.removeIf(other -> other.getTaskId().equals(taskId));
        return tasks;
    }

    public void delete(Long serverId, Long boardId, Long taskId, Long actorUserId) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "DELETE_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        TaskResponse before = TaskResponse.from(task);
        taskService.deleteById(task.getTaskId());
        events.publishEvent(DomainEvent.deleted(EventType.TASK_DELETED, serverId, boardId, taskId, actorUserId,
                before));
    }

    /** The priority level, checked to belong to the board; null for none. */
    /**
     * Fields of a feature the server has switched off keep what the task has (nothing, for a new
     * task), so saving a task where they are hidden never clears them.
     */
    private void keepSwitchedOffFields(Long serverId, Task task, TaskRequest request) {
        if (!features.isEnabled(serverId, Feature.PRIORITIES)) {
            request.setPriorityId(task == null ? null : task.getPriorityId());
        }
        if (!features.isEnabled(serverId, Feature.DUE_DATES)) {
            request.setDueDate(task == null ? null : task.getDueDate());
        }
    }

    private Long priorityIn(Long boardId, Long priorityId) {
        return priorityId == null ? null : boardPriorityService.requireInBoard(priorityId, boardId).getPriorityId();
    }

    private User requireUser(Long userId) {
        return userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    }

    private static boolean contentChanged(Task task, TaskRequest request) {
        return !Objects.equals(task.getTitle(), request.getTitle())
                || !Objects.equals(task.getDescription(), request.getDescription())
                || !Objects.equals(task.getPriorityId(), request.getPriorityId())
                || (request.getDueDate() != null && !request.getDueDate().equals(task.getDueDate()))
                || (request.getMetadata() != null && !request.getMetadata().equals(task.getMetadata()));
    }

    private static boolean moved(Task task, TaskRequest request) {
        boolean columnChanged = request.getColumnId() != null
                && !request.getColumnId().equals(task.getColumn().getColumnId());
        boolean positionChanged = request.getPosition() != null
                && (task.getPosition() == null || request.getPosition().compareTo(task.getPosition()) != 0);
        return columnChanged || positionChanged;
    }
}
