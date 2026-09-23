package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskLabelRequest;
import com.kanbancord_api.dto.TaskLabelResponse;
import com.kanbancord_api.model.Label;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskLabel;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskLabelService;
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
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public TaskLabelController(
            TaskLabelService taskLabelService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.taskLabelService = taskLabelService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @PostMapping
    public ResponseEntity<TaskLabelResponse> createTaskLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskLabelRequest request,
            @CurrentUser Long userId) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "APPLY_LABEL_TO_TASK");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        Label label = resourceValidator.requireLabelInServer(request.getLabelId(), serverId);
        resourceValidator.validateLabelBelongsToBoard(label.getLabelId(), boardId);

        TaskLabel taskLabel = new TaskLabel();
        taskLabel.setTask(task);
        taskLabel.setLabel(label);

        TaskLabel created = taskLabelService.create(taskLabel);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<TaskLabelResponse>> getTaskLabelsByTaskId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @CurrentUser Long userId) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        resourceValidator.requireTaskInServer(taskId, serverId);

        List<TaskLabel> taskLabels = taskLabelService.findByTaskId(taskId);

        List<TaskLabelResponse> responses = taskLabels.stream()
                .map(this::mapToResponse)
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

        accessValidator.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskLabel taskLabel = resourceValidator.requireTaskLabelInServer(taskLabelId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, taskLabel.getTask().getTaskId());

        return ResponseEntity.ok(mapToResponse(taskLabel));
    }

    @DeleteMapping("/{taskLabelId}")
    public ResponseEntity<Void> deleteTaskLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long taskLabelId,
            @CurrentUser Long userId) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "REMOVE_LABEL_FROM_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskLabel taskLabel = resourceValidator.requireTaskLabelInServer(taskLabelId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, taskLabel.getTask().getTaskId());

        taskLabelService.deleteById(taskLabel.getId());
        return ResponseEntity.noContent().build();
    }

    private TaskLabelResponse mapToResponse(TaskLabel taskLabel) {
        TaskLabelResponse response = new TaskLabelResponse();
        response.setId(taskLabel.getId());
        response.setTaskId(taskLabel.getTask().getTaskId());
        response.setLabelId(taskLabel.getLabel().getLabelId());
        response.setAddedAt(taskLabel.getAddedAt());
        return response;
    }
}
