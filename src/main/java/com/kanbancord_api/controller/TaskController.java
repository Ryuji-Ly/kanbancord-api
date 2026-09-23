package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskRequest;
import com.kanbancord_api.dto.TaskResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.User;
import com.kanbancord_api.realtime.RealtimeEventPublisher;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskService;
import com.kanbancord_api.service.UserService;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks")
@Validated
public class TaskController {

    private final TaskService taskService;
    private final BoardColumnService boardColumnService;
    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final RealtimeEventPublisher realtimeEventPublisher;

    public TaskController(
            TaskService taskService,
            BoardColumnService boardColumnService,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            RealtimeEventPublisher realtimeEventPublisher) {
        this.taskService = taskService;
        this.boardColumnService = boardColumnService;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.realtimeEventPublisher = realtimeEventPublisher;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody TaskRequest request,
            @CurrentUser Long userId) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "CREATE_TASK");
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

        // The creator is always the authenticated user; request.createdBy is ignored.
        User creator = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
        task.setCreatedBy(creator);

        Task created = taskService.create(task);
        TaskResponse response = mapToResponse(created);
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_CREATED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK",
                        created.getTaskId(),
                        userId,
                        response));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<TaskResponse>> getAllTasks(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) Long columnId,
            @CurrentUser Long userId,
            Pageable pageable) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");

        resourceValidator.requireBoardInServer(boardId, serverId);

        Page<Task> tasks;
        if (archived != null) {
            tasks = taskService.findByBoardIdAndArchived(boardId, archived, pageable);
        } else if (columnId != null) {
            resourceValidator.validateColumnBelongsToBoard(columnId, boardId);
            tasks = taskService.findByColumnIdOrdered(columnId, pageable);
        } else {
            tasks = taskService.findByBoardId(boardId, pageable);
        }

        Page<TaskResponse> responses = tasks.map(this::mapToResponse);

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTaskById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @CurrentUser Long userId) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        return ResponseEntity.ok(mapToResponse(task));
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<TaskResponse> updateTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskRequest request,
            @CurrentUser Long userId) {

        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        // Require only the permissions for what actually changes: moving a task must not need
        // EDIT_TASK, and editing a task in place must not need MOVE_TASK.
        boolean contentChanged = contentChanged(task, request);
        boolean moved = moved(task, request);
        if (!contentChanged && !moved) {
            accessValidator.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
            return ResponseEntity.ok(mapToResponse(task));
        }
        if (contentChanged) {
            accessValidator.requireBoardPermission(userId, serverId, boardId, "EDIT_TASK");
        }
        if (moved) {
            accessValidator.requireBoardPermission(userId, serverId, boardId, "MOVE_TASK");
        }

        resourceValidator.validateTaskNotArchived(task);

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

        Task updated = taskService.update(task);
        TaskResponse response = mapToResponse(updated);
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_UPDATED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK",
                        updated.getTaskId(),
                        userId,
                        response));
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @CurrentUser Long userId) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "DELETE_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);
        TaskResponse response = mapToResponse(task);

        taskService.deleteById(task.getTaskId());
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_DELETED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK",
                        taskId,
                        userId,
                        response));
        return ResponseEntity.noContent().build();
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

    private TaskResponse mapToResponse(Task task) {
        TaskResponse response = new TaskResponse();
        response.setTaskId(task.getTaskId());
        response.setBoardId(task.getBoard().getBoardId());
        response.setColumnId(task.getColumn().getColumnId());
        response.setTitle(task.getTitle());
        response.setDescription(task.getDescription());
        response.setPosition(task.getPosition());
        response.setPriority(task.getPriority());
        response.setDueDate(task.getDueDate());
        response.setIsArchived(task.getIsArchived());
        response.setMetadata(task.getMetadata());
        if (task.getCreatedBy() != null) {
            response.setCreatedBy(task.getCreatedBy().getUserId());
        }
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        response.setCompletedAt(task.getCompletedAt());
        return response;
    }
}
