package com.kanbancord_api.label;

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
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/labels")
@Validated
public class TaskLabelController {

    private final TaskLabelService taskLabelService;
    private final TaskLabelCommands taskLabelCommands;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public TaskLabelController(
            TaskLabelService taskLabelService,
            TaskLabelCommands taskLabelCommands,
            Authorizer authorizer,
            ResourceValidator resourceValidator) {
        this.taskLabelService = taskLabelService;
        this.taskLabelCommands = taskLabelCommands;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    @PostMapping
    public ResponseEntity<TaskLabelResponse> createTaskLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskLabelRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskLabelCommands.add(serverId, boardId, taskId, userId, request));
    }

    @GetMapping
    public ResponseEntity<List<TaskLabelResponse>> getTaskLabelsByTaskId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        resourceValidator.requireTaskInServer(taskId, serverId);

        List<TaskLabel> taskLabels = taskLabelService.findByTaskId(taskId);

        List<TaskLabelResponse> responses = taskLabels.stream()
                .map(TaskLabelResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{taskLabelId}")
    public ResponseEntity<TaskLabelResponse> getTaskLabelById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long taskLabelId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskLabel taskLabel = resourceValidator.requireTaskLabelInServer(taskLabelId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, taskLabel.getTask().getTaskId());

        return ResponseEntity.ok(TaskLabelResponse.from(taskLabel));
    }

    @DeleteMapping("/{taskLabelId}")
    public ResponseEntity<Void> deleteTaskLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long taskLabelId,
            @CurrentUser Long userId) {
        taskLabelCommands.remove(serverId, boardId, taskId, taskLabelId, userId);
        return ResponseEntity.noContent().build();
    }
}
