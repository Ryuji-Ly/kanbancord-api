package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskRequest;
import com.kanbancord_api.dto.TaskResponse;
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
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks")
@Validated
public class TaskController {

    private final TaskService taskService;
    private final BoardColumnService boardColumnService;
    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public TaskController(
            TaskService taskService,
            BoardColumnService boardColumnService,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.taskService = taskService;
        this.boardColumnService = boardColumnService;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody TaskRequest request,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "CREATE_TASK");
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

        Long creatorId = request.getCreatedBy() != null ? request.getCreatedBy() : userId;
        User creator = userService.findById(creatorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", creatorId));
        task.setCreatedBy(creator);

        Task created = taskService.create(task);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(created));
    }

    @GetMapping
    public ResponseEntity<Page<TaskResponse>> getAllTasks(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) Long columnId,
            @RequestParam Long userId,
            Pageable pageable) {

        accessValidator.requireUserInServer(userId, serverId);

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
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
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
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "EDIT_TASK");
        if (request.getColumnId() != null) {
            accessValidator.requireServerPermission(userId, serverId, "MOVE_TASK");
        }
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

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
        return ResponseEntity.ok(mapToResponse(updated));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "DELETE_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        taskService.deleteById(task.getTaskId());
        return ResponseEntity.noContent().build();
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
