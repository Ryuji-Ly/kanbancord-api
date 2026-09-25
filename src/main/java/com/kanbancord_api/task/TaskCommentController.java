package com.kanbancord_api.task;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/comments")
@Validated
public class TaskCommentController {

    private final TaskCommentService taskCommentService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final TaskCommentCommands taskCommentCommands;
    private final ServerFeatureService features;

    public TaskCommentController(
            TaskCommentService taskCommentService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            TaskCommentCommands taskCommentCommands,
            ServerFeatureService features) {
        this.taskCommentService = taskCommentService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.taskCommentCommands = taskCommentCommands;
        this.features = features;
    }

    @PostMapping
    public ResponseEntity<TaskCommentResponse> createComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskCommentRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskCommentCommands.create(serverId, boardId, taskId, userId, request));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Page<TaskCommentResponse>> getCommentsByTaskId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @RequestParam(required = false) Boolean activeOnly,
            @CurrentUser Long userId,
            Pageable pageable) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        features.require(serverId, Feature.COMMENTS);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        resourceValidator.requireTaskInServer(taskId, serverId);

        Page<TaskComment> comments;
        if (activeOnly != null && activeOnly) {
            comments = taskCommentService.findActiveByTaskId(taskId, pageable);
        } else {
            comments = taskCommentService.findByTaskId(taskId, pageable);
        }

        Page<TaskCommentResponse> responses = comments.map(TaskCommentResponse::from);

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{commentId}")
    @Transactional(readOnly = true)
    public ResponseEntity<TaskCommentResponse> getCommentById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        features.require(serverId, Feature.COMMENTS);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskComment comment = resourceValidator.requireCommentInServer(commentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, comment.getTask().getTaskId());

        return ResponseEntity.ok(TaskCommentResponse.from(comment));
    }

    @PutMapping("/{commentId}")
    public ResponseEntity<TaskCommentResponse> updateComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @Valid @RequestBody TaskCommentRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(taskCommentCommands.edit(serverId, boardId, taskId, commentId, userId, request));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @CurrentUser Long userId) {
        taskCommentCommands.delete(serverId, boardId, taskId, commentId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Authors manage their own comments with the same permission that lets them comment at all;
     * EDIT_TASK_COMMENT / DELETE_TASK_COMMENT are moderation permissions for other people's comments.
     */
    private static boolean isAuthor(TaskComment comment, Long userId) {
        return comment.getUser() != null && userId.equals(comment.getUser().getUserId());
    }
}
