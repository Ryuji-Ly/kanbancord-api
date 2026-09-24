package com.kanbancord_api.command;

import com.kanbancord_api.dto.TaskRequest;
import com.kanbancord_api.dto.TaskResponse;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskService;
import com.kanbancord_api.service.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public TaskCommands(
            TaskService taskService,
            BoardColumnService boardColumnService,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events) {
        this.taskService = taskService;
        this.boardColumnService = boardColumnService;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    public TaskResponse create(Long serverId, Long boardId, Long actorUserId, TaskRequest request) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_TASK");
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
        task.setPosition(request.getPosition());
        task.setPriority(request.getPriority());
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

        boolean contentChanged = contentChanged(task, request);
        boolean moved = moved(task, request);
        if (!contentChanged && !moved) {
            accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_TASK");
            return TaskResponse.from(task);
        }
        if (contentChanged) {
            accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "EDIT_TASK");
        }
        if (moved) {
            accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_TASK");
        }
        resourceValidator.validateTaskNotArchived(task);

        TaskResponse before = TaskResponse.from(task);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        if (request.getPosition() != null) {
            task.setPosition(request.getPosition());
        }
        if (request.getPriority() != null) {
            task.setPriority(request.getPriority());
        }
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

    public void delete(Long serverId, Long boardId, Long taskId, Long actorUserId) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "DELETE_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        TaskResponse before = TaskResponse.from(task);
        taskService.deleteById(task.getTaskId());
        events.publishEvent(DomainEvent.deleted(EventType.TASK_DELETED, serverId, boardId, taskId, actorUserId,
                before));
    }

    private User requireUser(Long userId) {
        return userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    }

    private static boolean contentChanged(Task task, TaskRequest request) {
        return !Objects.equals(task.getTitle(), request.getTitle())
                || !Objects.equals(task.getDescription(), request.getDescription())
                || (request.getPriority() != null && !request.getPriority().equals(task.getPriority()))
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
