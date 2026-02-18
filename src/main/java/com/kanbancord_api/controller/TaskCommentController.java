package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskCommentRequest;
import com.kanbancord_api.dto.TaskCommentResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskComment;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.ServerAccessValidator;
import com.kanbancord_api.service.TaskCommentService;
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
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/comments")
@Validated
public class TaskCommentController {

    private final TaskCommentService taskCommentService;
    private final TaskService taskService;
    private final UserService userService;
    private final ServerAccessValidator accessValidator;

    public TaskCommentController(
            TaskCommentService taskCommentService,
            TaskService taskService,
            UserService userService,
            ServerAccessValidator accessValidator) {
        this.taskCommentService = taskCommentService;
        this.taskService = taskService;
        this.userService = userService;
        this.accessValidator = accessValidator;
    }

    @PostMapping
    public ResponseEntity<TaskCommentResponse> createComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskCommentRequest request,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Task task = taskService.findByIdAndServerId(taskId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", "taskId", taskId));

        User user = userService.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", request.getUserId()));

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(user);
        comment.setContent(request.getContent());

        if (request.getReplyToId() != null) {
            TaskComment replyTo = taskCommentService.findByIdAndServerId(request.getReplyToId(), serverId)
                    .orElseThrow(
                            () -> new ResourceNotFoundException("TaskComment", "commentId", request.getReplyToId()));
            comment.setReplyTo(replyTo);
        }

        TaskComment created = taskCommentService.create(comment);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(created));
    }

    @GetMapping
    public ResponseEntity<Page<TaskCommentResponse>> getCommentsByTaskId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) Long userId,
            Pageable pageable) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        taskService.findByIdAndServerId(taskId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", "taskId", taskId));

        Page<TaskComment> comments;
        if (activeOnly != null && activeOnly) {
            comments = taskCommentService.findActiveByTaskId(taskId, pageable);
        } else {
            comments = taskCommentService.findByTaskId(taskId, pageable);
        }

        Page<TaskCommentResponse> responses = comments.map(this::mapToResponse);

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{commentId}")
    public ResponseEntity<TaskCommentResponse> getCommentById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        TaskComment comment = taskCommentService.findByIdAndServerId(commentId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskComment", "commentId", commentId));

        return ResponseEntity.ok(mapToResponse(comment));
    }

    @PutMapping("/{commentId}")
    public ResponseEntity<TaskCommentResponse> updateComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @Valid @RequestBody TaskCommentRequest request,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        TaskComment comment = taskCommentService.findByIdAndServerId(commentId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskComment", "commentId", commentId));

        comment.setContent(request.getContent());

        TaskComment updated = taskCommentService.update(comment);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        TaskComment comment = taskCommentService.findByIdAndServerId(commentId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskComment", "commentId", commentId));

        taskCommentService.deleteById(comment.getCommentId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{commentId}/soft-delete")
    public ResponseEntity<Void> softDeleteComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        TaskComment comment = taskCommentService.findByIdAndServerId(commentId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskComment", "commentId", commentId));

        taskCommentService.softDelete(comment.getCommentId());
        return ResponseEntity.noContent().build();
    }

    private TaskCommentResponse mapToResponse(TaskComment comment) {
        TaskCommentResponse response = new TaskCommentResponse();
        response.setCommentId(comment.getCommentId());
        response.setTaskId(comment.getTask().getTaskId());
        response.setUserId(comment.getUser().getUserId());
        response.setContent(comment.getContent());
        if (comment.getReplyTo() != null) {
            response.setReplyToId(comment.getReplyTo().getCommentId());
        }
        response.setCreatedAt(comment.getCreatedAt());
        response.setUpdatedAt(comment.getUpdatedAt());
        response.setDeletedAt(comment.getDeletedAt());
        return response;
    }
}
