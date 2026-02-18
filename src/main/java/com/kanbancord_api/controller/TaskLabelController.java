package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskLabelRequest;
import com.kanbancord_api.dto.TaskLabelResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Label;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskLabel;
import com.kanbancord_api.service.LabelService;
import com.kanbancord_api.service.ServerAccessValidator;
import com.kanbancord_api.service.TaskLabelService;
import com.kanbancord_api.service.TaskService;
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
    private final TaskService taskService;
    private final LabelService labelService;
    private final ServerAccessValidator accessValidator;

    public TaskLabelController(
            TaskLabelService taskLabelService,
            TaskService taskService,
            LabelService labelService,
            ServerAccessValidator accessValidator) {
        this.taskLabelService = taskLabelService;
        this.taskService = taskService;
        this.labelService = labelService;
        this.accessValidator = accessValidator;
    }

    @PostMapping
    public ResponseEntity<TaskLabelResponse> createTaskLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskLabelRequest request,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Task task = taskService.findByIdAndServerId(taskId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", "taskId", taskId));

        Label label = labelService.findByIdAndServerId(request.getLabelId(), serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", "labelId", request.getLabelId()));

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
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        taskService.findByIdAndServerId(taskId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", "taskId", taskId));

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
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        TaskLabel taskLabel = taskLabelService.findByIdAndServerId(taskLabelId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskLabel", "taskLabelId", taskLabelId));

        return ResponseEntity.ok(mapToResponse(taskLabel));
    }

    @DeleteMapping("/{taskLabelId}")
    public ResponseEntity<Void> deleteTaskLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long taskLabelId,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        TaskLabel taskLabel = taskLabelService.findByIdAndServerId(taskLabelId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskLabel", "taskLabelId", taskLabelId));

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
