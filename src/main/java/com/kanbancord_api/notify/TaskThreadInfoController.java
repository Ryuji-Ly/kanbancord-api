package com.kanbancord_api.notify;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * One task's thread, for the "Discuss in thread" button: whether it has one, and if not, the thread
 * the bot should make (or, with threads off, whether the person asking may switch them on, and where).
 */
@RestController
public class TaskThreadInfoController {

    private final TaskThreadService threads;
    private final PermissionEvaluationService permissions;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public TaskThreadInfoController(TaskThreadService threads, PermissionEvaluationService permissions,
                                    Authorizer authorizer, ResourceValidator resourceValidator) {
        this.threads = threads;
        this.permissions = permissions;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/thread")
    public ResponseEntity<TaskThreadService.TaskThreadInfo> get(@PathVariable Long serverId, @PathVariable Long boardId,
                                                                @PathVariable Long taskId, @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        boolean canEnable = permissions.isAllowed(serverId, boardId, userId, "EDIT_BOARD_DETAILS");
        return ResponseEntity.ok(threads.info(serverId, boardId, taskId, canEnable));
    }
}
