package com.kanbancord_api.task;

import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Roles assigned to a task. They are listed in the board snapshot. */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/role-assignments")
public class TaskRoleAssignmentController {

    private final TaskRoleAssignmentCommands commands;

    public TaskRoleAssignmentController(TaskRoleAssignmentCommands commands) {
        this.commands = commands;
    }

    @PostMapping
    public ResponseEntity<TaskRoleAssignmentResponse> assign(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskRoleAssignmentRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commands.assign(serverId, boardId, taskId, userId, request));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void> unassign(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long assignmentId,
            @CurrentUser Long userId) {
        commands.unassign(serverId, boardId, taskId, assignmentId, userId);
        return ResponseEntity.noContent().build();
    }
}
