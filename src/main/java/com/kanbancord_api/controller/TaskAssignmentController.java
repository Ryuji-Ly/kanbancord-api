package com.kanbancord_api.controller;

import com.kanbancord_api.dto.TaskAssignmentRequest;
import com.kanbancord_api.dto.TaskAssignmentResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskAssignment;
import com.kanbancord_api.model.User;
import com.kanbancord_api.realtime.RealtimeEventPublisher;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskAssignmentService;
import com.kanbancord_api.service.UserService;
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
    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final RealtimeEventPublisher realtimeEventPublisher;

    public TaskAssignmentController(
            TaskAssignmentService taskAssignmentService,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            RealtimeEventPublisher realtimeEventPublisher) {
        this.taskAssignmentService = taskAssignmentService;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.realtimeEventPublisher = realtimeEventPublisher;
    }

    @PostMapping
    public ResponseEntity<TaskAssignmentResponse> createTaskAssignment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskAssignmentRequest request,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        User user = userService.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", request.getUserId()));

        User assignedBy = userService.findById(request.getAssignedBy())
                .orElseThrow(() -> new ResourceNotFoundException("User", "assignedBy", request.getAssignedBy()));

        TaskAssignment taskAssignment = new TaskAssignment();
        taskAssignment.setTask(task);
        taskAssignment.setUser(user);
        taskAssignment.setAssignedBy(assignedBy);

        TaskAssignment created = taskAssignmentService.create(taskAssignment);
        TaskAssignmentResponse response = mapToResponse(created);
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_ASSIGNMENT_CREATED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK_ASSIGNMENT",
                        created.getId(),
                        userId,
                        response));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<TaskAssignmentResponse>> getAssignmentsByTaskId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        resourceValidator.requireTaskInServer(taskId, serverId);

        List<TaskAssignment> assignments = taskAssignmentService.findByTaskId(taskId);

        List<TaskAssignmentResponse> responses = assignments.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<TaskAssignmentResponse> getTaskAssignmentById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long assignmentId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskAssignment assignment = resourceValidator.requireAssignmentInServer(assignmentId, serverId);

        resourceValidator.validatePathMatchesRequestId("taskId", taskId, assignment.getTask().getTaskId());

        return ResponseEntity.ok(mapToResponse(assignment));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void> deleteTaskAssignment(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long taskId,
            @PathVariable Long assignmentId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);

        TaskAssignment assignment = resourceValidator.requireAssignmentInServer(assignmentId, serverId);

        resourceValidator.validatePathMatchesRequestId("taskId", taskId, assignment.getTask().getTaskId());

        TaskAssignmentResponse response = mapToResponse(assignment);
        taskAssignmentService.deleteById(assignment.getId());
        realtimeEventPublisher.publishToBoardTopic(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "TASK_ASSIGNMENT_DELETED",
                        "BOARD",
                        serverId,
                        boardId,
                        "TASK_ASSIGNMENT",
                        assignmentId,
                        userId,
                        response));
        return ResponseEntity.noContent().build();
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
