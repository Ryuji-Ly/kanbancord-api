package com.kanbancord_api.task;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
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
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final ServerFeatureService features;

    public TaskAssignmentCommands(
            TaskAssignmentService taskAssignmentService,
            UserService userService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            ServerFeatureService features) {
        this.taskAssignmentService = taskAssignmentService;
        this.userService = userService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.features = features;
    }

    public TaskAssignmentResponse assign(Long serverId, Long boardId, Long taskId, Long actorUserId,
            TaskAssignmentRequest request) {
        features.require(serverId, boardId, Feature.ASSIGNEES);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.taskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId,
                assignPermissionFor(actorUserId, request.userId()));
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", request.userId(), serverId);

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTask(task);
        assignment.setUser(requireUser(request.userId()));
        // The assigner is always the actor; request.assignedBy is ignored.
        assignment.setAssignedBy(requireUser(actorUserId));

        TaskAssignmentResponse created = TaskAssignmentResponse.from(taskAssignmentService.create(assignment));
        events.publishEvent(DomainEvent.created(EventType.TASK_ASSIGNMENT_CREATED, serverId, boardId,
                created.id(), actorUserId, created));
        return created;
    }

    public void unassign(Long serverId, Long boardId, Long taskId, Long assignmentId, Long actorUserId) {
        features.require(serverId, boardId, Feature.ASSIGNEES);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        TaskAssignment assignment = resourceValidator.requireAssignmentInServer(assignmentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, assignment.getTask().getTaskId());
        authorizer.requireBoardPermission(actorUserId, serverId, boardId,
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
