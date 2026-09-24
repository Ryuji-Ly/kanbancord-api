package com.kanbancord_api.controller;

import com.kanbancord_api.command.TaskCommands;
import com.kanbancord_api.dto.TaskMoveRequest;
import com.kanbancord_api.dto.TaskRequest;
import com.kanbancord_api.dto.TaskResponse;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskService;
import com.kanbancord_api.security.CurrentUser;
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
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final TaskCommands taskCommands;

    public TaskController(
            TaskService taskService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            TaskCommands taskCommands) {
        this.taskService = taskService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.taskCommands = taskCommands;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody TaskRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskCommands.create(serverId, boardId, userId, request));
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

        Page<TaskResponse> responses = tasks.map(TaskResponse::from);

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

        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<TaskResponse> updateTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(taskCommands.update(serverId, boardId, taskId, userId, request));
    }

    @PostMapping("/{taskId}/move")
    public ResponseEntity<TaskResponse> moveTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskMoveRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(taskCommands.move(serverId, boardId, taskId, userId, request));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @CurrentUser Long userId) {
        taskCommands.delete(serverId, boardId, taskId, userId);
        return ResponseEntity.noContent().build();
    }
}
