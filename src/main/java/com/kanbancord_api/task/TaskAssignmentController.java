package com.kanbancord_api.task;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/assignments")
@Validated
public class TaskAssignmentController {

    private final TaskAssignmentService taskAssignmentService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final TaskAssignmentCommands taskAssignmentCommands;

    public TaskAssignmentController(
            TaskAssignmentService taskAssignmentService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            TaskAssignmentCommands taskAssignmentCommands) {
        this.taskAssignmentService = taskAssignmentService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.taskAssignmentCommands = taskAssignmentCommands;
    }

    @PostMapping
    public ResponseEntity<TaskAssignmentResponse> createTaskAssignment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskAssignmentRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskAssignmentCommands.assign(serverId, boardId, taskId, userId, request));
    }

    @GetMapping
    public ResponseEntity<List<TaskAssignmentResponse>> getAssignmentsByTaskId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        resourceValidator.requireTaskInServer(taskId, serverId);

        List<TaskAssignment> assignments = taskAssignmentService.findByTaskId(taskId);

        List<TaskAssignmentResponse> responses = assignments.stream()
                .map(TaskAssignmentResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<TaskAssignmentResponse> getTaskAssignmentById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long assignmentId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskAssignment assignment = resourceValidator.requireAssignmentInServer(assignmentId, serverId);

        resourceValidator.validatePathMatchesRequestId("taskId", taskId, assignment.getTask().getTaskId());

        return ResponseEntity.ok(TaskAssignmentResponse.from(assignment));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void> deleteTaskAssignment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long assignmentId,
            @CurrentUser Long userId) {
        taskAssignmentCommands.unassign(serverId, boardId, taskId, assignmentId, userId);
        return ResponseEntity.noContent().build();
    }

    /** Assigning or unassigning yourself needs ASSIGN_TASK_SELF; anyone else needs ASSIGN_TASK_OTHERS. */
    private static String assignPermissionFor(Long actorUserId, Long assigneeUserId) {
        return actorUserId.equals(assigneeUserId) ? "ASSIGN_TASK_SELF" : "ASSIGN_TASK_OTHERS";
    }
}
