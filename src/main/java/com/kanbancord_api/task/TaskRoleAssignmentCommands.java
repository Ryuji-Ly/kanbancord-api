package com.kanbancord_api.task;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import com.kanbancord_api.server.RoleService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigning Discord roles to tasks, part of the assignees feature. Assigning a role is assigning
 * other people, so it needs ASSIGN_TASK_OTHERS. It is informational: it grants the role nothing.
 */
@Service
@Transactional
public class TaskRoleAssignmentCommands {

    private final TaskRoleAssignmentRepository repository;
    private final RoleService roleService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final ServerFeatureService features;

    public TaskRoleAssignmentCommands(
            TaskRoleAssignmentRepository repository,
            RoleService roleService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            ServerFeatureService features) {
        this.repository = repository;
        this.roleService = roleService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.features = features;
    }

    public TaskRoleAssignmentResponse assign(Long serverId, Long boardId, Long taskId, Long actorUserId,
            TaskRoleAssignmentRequest request) {
        requireAllowed(serverId, boardId, taskId, actorUserId);
        Long roleId = request.getRoleId();
        roleService.findById(roleId)
                .filter(role -> role.getServer() != null && serverId.equals(role.getServer().getServerId()))
                .orElseThrow(() -> new BadRequestException("Role " + roleId + " is not a role of this server"));
        if (repository.existsByTaskIdAndRoleId(taskId, roleId)) {
            throw new BadRequestException("The role is already assigned to this task");
        }

        TaskRoleAssignment assignment = new TaskRoleAssignment();
        assignment.setTaskId(taskId);
        assignment.setRoleId(roleId);
        assignment.setAssignedBy(actorUserId);
        TaskRoleAssignmentResponse created = TaskRoleAssignmentResponse.from(repository.saveAndFlush(assignment));
        events.publishEvent(DomainEvent.created(EventType.TASK_ROLE_ASSIGNED, serverId, boardId, created.id(),
                actorUserId, created));
        return created;
    }

    public void unassign(Long serverId, Long boardId, Long taskId, Long assignmentId, Long actorUserId) {
        requireAllowed(serverId, boardId, taskId, actorUserId);
        TaskRoleAssignment assignment = repository.findById(assignmentId)
                .filter(candidate -> candidate.getTaskId().equals(taskId))
                .orElseThrow(() -> new ResourceNotFoundException("TaskRoleAssignment", "id", assignmentId));

        TaskRoleAssignmentResponse before = TaskRoleAssignmentResponse.from(assignment);
        repository.delete(assignment);
        events.publishEvent(DomainEvent.deleted(EventType.TASK_ROLE_UNASSIGNED, serverId, boardId, assignmentId,
                actorUserId, before));
    }

    private void requireAllowed(Long serverId, Long boardId, Long taskId, Long actorUserId) {
        features.require(serverId, boardId, Feature.ASSIGNEES);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "ASSIGN_TASK_OTHERS");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        resourceValidator.requireTaskInServer(taskId, serverId);
    }
}
