package com.kanbancord_api.task;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Following a task: the follower hears about it by direct message, like the people assigned to it.
 * Anyone who can see a task may follow it; following is personal, so it is not in the audit log.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/follow")
public class TaskFollowController {

    private final TaskFollowService follows;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public TaskFollowController(TaskFollowService follows, Authorizer authorizer, ResourceValidator resourceValidator) {
        this.follows = follows;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    public record FollowResponse(boolean following) {
    }

    @PutMapping
    public ResponseEntity<FollowResponse> follow(@PathVariable Long serverId, @PathVariable Long boardId,
                                                 @PathVariable Long taskId, @CurrentUser Long userId) {
        requireVisible(serverId, boardId, taskId, userId);
        follows.follow(taskId, userId);
        return ResponseEntity.ok(new FollowResponse(true));
    }

    /** Unfollowing a task one does not follow is not an error. */
    @DeleteMapping
    public ResponseEntity<FollowResponse> unfollow(@PathVariable Long serverId, @PathVariable Long boardId,
                                                   @PathVariable Long taskId, @CurrentUser Long userId) {
        requireVisible(serverId, boardId, taskId, userId);
        follows.unfollow(taskId, userId);
        return ResponseEntity.ok(new FollowResponse(false));
    }

    private void requireVisible(Long serverId, Long boardId, Long taskId, Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.requireTaskInServer(taskId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
    }
}
