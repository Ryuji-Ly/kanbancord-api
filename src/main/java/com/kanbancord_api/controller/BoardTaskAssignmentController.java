package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskAssignmentResponse;
import com.kanbancord_api.model.TaskAssignment;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskAssignmentService;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/task-assignments")
@Validated
public class BoardTaskAssignmentController {

    private final TaskAssignmentService taskAssignmentService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public BoardTaskAssignmentController(
            TaskAssignmentService taskAssignmentService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.taskAssignmentService = taskAssignmentService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping
    public ResponseEntity<List<TaskAssignmentResponse>> getAssignmentsByBoardId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {

        accessValidator.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);

        List<TaskAssignmentResponse> responses = taskAssignmentService.findByBoardId(boardId).stream()
                .map(this::mapToResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    private TaskAssignmentResponse mapToResponse(TaskAssignment assignment) {
        TaskAssignmentResponse response = new TaskAssignmentResponse();
        response.setId(assignment.getId());
        response.setTaskId(assignment.getTask().getTaskId());
        response.setUserId(assignment.getUser().getUserId());
        response.setAssignedBy(assignment.getAssignedBy().getUserId());
        response.setAssignedAt(assignment.getAssignedAt());
        return response;
    }
}