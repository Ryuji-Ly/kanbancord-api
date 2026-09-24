package com.kanbancord_api.command;

import com.kanbancord_api.dto.TaskAssignmentRequest;
import com.kanbancord_api.dto.TaskAssignmentResponse;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskAssignment;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskAssignmentService;
import com.kanbancord_api.service.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigning and unassigning users. Doing it for yourself needs ASSIGN_TASK_SELF; for anyone else,
 * ASSIGN_TASK_OTHERS.
 */
@Service
@Transactional
public class TaskAssignmentCommands {

    private final TaskAssignmentService taskAssignmentService;
    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public TaskAssignmentCommands(
            TaskAssignmentService taskAssignmentService,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events) {
        this.taskAssignmentService = taskAssignmentService;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    public TaskAssignmentResponse assign(Long serverId, Long boardId, Long taskId, Long actorUserId,
            TaskAssignmentRequest request) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId,
                assignPermissionFor(actorUserId, request.getUserId()));
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", request.getUserId(), serverId);

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTask(task);
        assignment.setUser(requireUser(request.getUserId()));
        // The assigner is always the actor; request.assignedBy is ignored.
        assignment.setAssignedBy(requireUser(actorUserId));

        TaskAssignmentResponse created = TaskAssignmentResponse.from(taskAssignmentService.create(assignment));
        events.publishEvent(DomainEvent.created(EventType.TASK_ASSIGNMENT_CREATED, serverId, boardId,
                created.getId(), actorUserId, created));
        return created;
    }

    public void unassign(Long serverId, Long boardId, Long taskId, Long assignmentId, Long actorUserId) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        TaskAssignment assignment = resourceValidator.requireAssignmentInServer(assignmentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, assignment.getTask().getTaskId());
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId,
                assignPermissionFor(actorUserId, assignment.getUser().getUserId()));

        TaskAssignmentResponse before = TaskAssignmentResponse.from(assignment);
        taskAssignmentService.deleteById(assignment.getId());
        events.publishEvent(DomainEvent.deleted(EventType.TASK_ASSIGNMENT_DELETED, serverId, boardId, assignmentId,
                actorUserId, before));
    }

    private User requireUser(Long userId) {
        return userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    }

    private static String assignPermissionFor(Long actorUserId, Long assigneeUserId) {
        return actorUserId.equals(assigneeUserId) ? "ASSIGN_TASK_SELF" : "ASSIGN_TASK_OTHERS";
    }
}
