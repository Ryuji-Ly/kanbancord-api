package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskCommentRequest;
import com.kanbancord_api.dto.TaskCommentEditorResponse;
import com.kanbancord_api.dto.TaskCommentResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskComment;
import com.kanbancord_api.model.TaskCommentEdit;
import com.kanbancord_api.model.User;
import com.kanbancord_api.realtime.RealtimeEventPublisher;
import com.kanbancord_api.repository.TaskCommentEditRepository;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskCommentService;
import com.kanbancord_api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/comments")
@Validated
@Transactional(readOnly = true)
public class TaskCommentController {

    private final TaskCommentService taskCommentService;
    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final TaskCommentEditRepository taskCommentEditRepository;
    private final RealtimeEventPublisher realtimeEventPublisher;

    public TaskCommentController(
            TaskCommentService taskCommentService,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            TaskCommentEditRepository taskCommentEditRepository,
            RealtimeEventPublisher realtimeEventPublisher) {
        this.taskCommentService = taskCommentService;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.taskCommentEditRepository = taskCommentEditRepository;
        this.realtimeEventPublisher = realtimeEventPublisher;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<TaskCommentResponse> createComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskCommentRequest request,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        accessValidator.requireServerPermission(userId, serverId, "CREATE_TASK_COMMENT");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        User user = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(user);
        comment.setContent(request.getContent());

        if (request.getReplyToId() != null) {
            TaskComment replyTo = resourceValidator.requireCommentInServer(request.getReplyToId(), serverId);
            resourceValidator.validatePathMatchesRequestId("taskId", taskId, replyTo.getTask().getTaskId());
            comment.setReplyTo(replyTo);
        }

        TaskComment created = taskCommentService.create(comment);
        TaskCommentResponse response = mapToResponse(created);
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_COMMENT_CREATED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK_COMMENT",
                        created.getCommentId(),
                        userId,
                        response));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<TaskCommentResponse>> getCommentsByTaskId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam Long userId,
            Pageable pageable) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        resourceValidator.requireTaskInServer(taskId, serverId);

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
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskComment comment = resourceValidator.requireCommentInServer(commentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, comment.getTask().getTaskId());

        return ResponseEntity.ok(mapToResponse(comment));
    }

    @PutMapping("/{commentId}")
    @Transactional
    public ResponseEntity<TaskCommentResponse> updateComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @Valid @RequestBody TaskCommentRequest request,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        accessValidator.requireServerPermission(userId, serverId, "EDIT_TASK_COMMENT");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskComment comment = resourceValidator.requireCommentInServer(commentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, comment.getTask().getTaskId());

        comment.setContent(request.getContent());

        TaskComment updated = taskCommentService.update(comment);
        User editor = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        TaskCommentEdit edit = new TaskCommentEdit();
        edit.setTaskComment(updated);
        edit.setEditor(editor);
        taskCommentEditRepository.save(edit);

        TaskComment refreshed = taskCommentService.findById(updated.getCommentId()).orElse(updated);
        TaskCommentResponse response = mapToResponse(refreshed);
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_COMMENT_UPDATED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK_COMMENT",
                        updated.getCommentId(),
                        userId,
                        response));

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{commentId}")
    @Transactional
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        accessValidator.requireServerPermission(userId, serverId, "DELETE_TASK_COMMENT");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskComment comment = resourceValidator.requireCommentInServer(commentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, comment.getTask().getTaskId());

        TaskCommentResponse response = mapToResponse(comment);
        taskCommentService.deleteById(comment.getCommentId());
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_COMMENT_DELETED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK_COMMENT",
                        commentId,
                        userId,
                        response));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{commentId}/soft-delete")
    @Transactional
    public ResponseEntity<Void> softDeleteComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskComment comment = resourceValidator.requireCommentInServer(commentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, comment.getTask().getTaskId());

        taskCommentService.softDelete(comment.getCommentId());
        TaskComment softDeleted = taskCommentService.findById(commentId).orElse(comment);
        TaskCommentResponse response = mapToResponse(softDeleted);
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_COMMENT_SOFT_DELETED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK_COMMENT",
                        commentId,
                        userId,
                        response));
        return ResponseEntity.noContent().build();
    }

    private TaskCommentResponse mapToResponse(TaskComment comment) {
        TaskCommentResponse response = new TaskCommentResponse();
        response.setCommentId(comment.getCommentId());
        response.setTaskId(comment.getTask().getTaskId());
        response.setUserId(comment.getUser().getUserId());
        response.setAuthorUsername(comment.getUser().getUsername());
        response.setAuthorGlobalName(comment.getUser().getGlobalName());
        response.setAuthorAvatarUrl(comment.getUser().getAvatarUrl());
        response.setContent(comment.getContent());
        if (comment.getReplyTo() != null) {
            response.setReplyToId(comment.getReplyTo().getCommentId());
        }
        response.setCreatedAt(comment.getCreatedAt());
        response.setUpdatedAt(comment.getUpdatedAt());
        response.setDeletedAt(comment.getDeletedAt());

        List<TaskCommentEditorResponse> editors = comment.getEdits().stream()
                .sorted(Comparator.comparing(TaskCommentEdit::getEditedAt))
                .map(TaskCommentEdit::getEditor)
                .collect(Collectors.toMap(
                        User::getUserId,
                        Function.identity(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .map(this::mapEditor)
                .collect(Collectors.toList());
        response.setEditedByUsers(editors);

        return response;
    }

    private TaskCommentEditorResponse mapEditor(User user) {
        TaskCommentEditorResponse response = new TaskCommentEditorResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setGlobalName(user.getGlobalName());
        response.setAvatarUrl(user.getAvatarUrl());
        return response;
    }
}
