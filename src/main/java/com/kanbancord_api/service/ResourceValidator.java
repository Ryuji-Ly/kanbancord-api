package com.kanbancord_api.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.model.AuditLog;
import com.kanbancord_api.model.Label;
import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.model.Notification;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskAssignment;
import com.kanbancord_api.model.TaskComment;
import com.kanbancord_api.model.TaskLabel;
import org.springframework.stereotype.Service;

@Service
public class ResourceValidator {

    private final BoardService boardService;
    private final BoardColumnService boardColumnService;
    private final TaskService taskService;
    private final LabelService labelService;
    private final TaskAssignmentService taskAssignmentService;
    private final TaskCommentService taskCommentService;
    private final TaskLabelService taskLabelService;
    private final RoleService roleService;
    private final ServerMemberService serverMemberService;
    private final MemberRoleService memberRoleService;
    private final AuditLogService auditLogService;
    private final PermissionService permissionService;
    private final BusinessValidationService businessValidationService;

    public ResourceValidator(
            BoardService boardService,
            BoardColumnService boardColumnService,
            TaskService taskService,
            LabelService labelService,
            TaskAssignmentService taskAssignmentService,
            TaskCommentService taskCommentService,
            TaskLabelService taskLabelService,
            RoleService roleService,
            ServerMemberService serverMemberService,
            MemberRoleService memberRoleService,
            AuditLogService auditLogService,
            PermissionService permissionService,
            BusinessValidationService businessValidationService) {
        this.boardService = boardService;
        this.boardColumnService = boardColumnService;
        this.taskService = taskService;
        this.labelService = labelService;
        this.taskAssignmentService = taskAssignmentService;
        this.taskCommentService = taskCommentService;
        this.taskLabelService = taskLabelService;
        this.roleService = roleService;
        this.serverMemberService = serverMemberService;
        this.memberRoleService = memberRoleService;
        this.auditLogService = auditLogService;
        this.permissionService = permissionService;
        this.businessValidationService = businessValidationService;
    }

    public void validatePathMatchesRequestId(String fieldName, Long pathId, Long requestId) {
        if (requestId != null && !pathId.equals(requestId)) {
            throw new BadRequestException(
                    "Path " + fieldName + " (" + pathId + ") does not match request " + fieldName + " (" + requestId
                            + ")");
        }
    }

    public Board requireBoardInServer(Long boardId, Long serverId) {
        return boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));
    }

    public BoardColumn requireColumnInServer(Long columnId, Long serverId) {
        return boardColumnService.findByIdAndServerId(columnId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", columnId));
    }

    public Task requireTaskInServer(Long taskId, Long serverId) {
        return taskService.findByIdAndServerId(taskId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", "taskId", taskId));
    }

    public Label requireLabelInServer(Long labelId, Long serverId) {
        return labelService.findByIdAndServerId(labelId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", "labelId", labelId));
    }

    public TaskAssignment requireAssignmentInServer(Long assignmentId, Long serverId) {
        return taskAssignmentService.findByIdAndServerId(assignmentId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskAssignment", "assignmentId", assignmentId));
    }

    public TaskComment requireCommentInServer(Long commentId, Long serverId) {
        return taskCommentService.findByIdAndServerId(commentId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskComment", "commentId", commentId));
    }

    public TaskLabel requireTaskLabelInServer(Long taskLabelId, Long serverId) {
        return taskLabelService.findByIdAndServerId(taskLabelId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskLabel", "taskLabelId", taskLabelId));
    }

    public ServerMember requireServerMemberInServer(Long memberId, Long serverId) {
        ServerMember member = serverMemberService.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("ServerMember", "id", memberId));

        if (!member.getServer().getServerId().equals(serverId)) {
            throw new ResourceNotFoundException("ServerMember", "id", memberId);
        }

        return member;
    }

    public Role requireRoleInServer(Long roleId, Long serverId) {
        Role role = roleService.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", "roleId", roleId));

        if (!role.getServer().getServerId().equals(serverId)) {
            throw new ResourceNotFoundException("Role", "roleId", roleId);
        }

        return role;
    }

    public MemberRole requireMemberRoleInServer(Long memberRoleId, Long serverId) {
        MemberRole memberRole = memberRoleService.findById(memberRoleId)
                .orElseThrow(() -> new ResourceNotFoundException("MemberRole", "id", memberRoleId));

        if (!memberRole.getServerMember().getServer().getServerId().equals(serverId)) {
            throw new ResourceNotFoundException("MemberRole", "id", memberRoleId);
        }

        return memberRole;
    }

    public AuditLog requireAuditLogInServer(Long logId, Long serverId) {
        AuditLog auditLog = auditLogService.findById(logId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog", "logId", logId));

        if (auditLog.getServer() == null || !auditLog.getServer().getServerId().equals(serverId)) {
            throw new ResourceNotFoundException("AuditLog", "logId", logId);
        }

        return auditLog;
    }

    public Permission requirePermissionInServer(Long permissionId, Long serverId) {
        Permission permission = permissionService.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission", "id", permissionId));

        if (!isPermissionInServer(permission, serverId)) {
            throw new ResourceNotFoundException("Permission", "id", permissionId);
        }

        return permission;
    }

    public void validatePermissionScopeBelongsToServer(String scopeType, Long scopeId, Long serverId) {
        if ("SERVER".equalsIgnoreCase(scopeType)) {
            if (!serverId.equals(scopeId)) {
                throw new BadRequestException("Server-scoped permission must use the current serverId as scopeId");
            }
            return;
        }

        if ("BOARD".equalsIgnoreCase(scopeType)) {
            requireBoardInServer(scopeId, serverId);
            return;
        }

        throw new BadRequestException("Unsupported scopeType: " + scopeType);
    }

    public void validatePermissionSubjectBelongsToServer(String subjectType, Long subjectId, Long serverId) {
        if ("ROLE".equalsIgnoreCase(subjectType)) {
            requireRoleInServer(subjectId, serverId);
            return;
        }

        if ("USER".equalsIgnoreCase(subjectType)) {
            boolean inServer = serverMemberService.existsByServerIdAndUserId(serverId, subjectId);
            if (!inServer) {
                throw new BadRequestException("User does not belong to the server");
            }
            return;
        }

        if (!"DISCORD_PERMISSION".equalsIgnoreCase(subjectType)) {
            throw new BadRequestException("Unsupported subjectType: " + subjectType);
        }
    }

    public void validateBoardBelongsToServer(Long boardId, Long serverId) {
        businessValidationService.validateBoardBelongsToServer(boardId, serverId);
    }

    public void validateColumnBelongsToBoard(Long columnId, Long boardId) {
        businessValidationService.validateColumnBelongsToBoard(columnId, boardId);
    }

    public void validateTaskBelongsToBoard(Long taskId, Long boardId) {
        businessValidationService.validateTaskBelongsToBoard(taskId, boardId);
    }

    public void validateLabelBelongsToBoard(Long labelId, Long boardId) {
        businessValidationService.validateLabelBelongsToBoard(labelId, boardId);
    }

    public void validateTaskMove(Task task, Long targetColumnId) {
        businessValidationService.validateTaskMove(task, targetColumnId);
    }

    public void validateTaskNotArchived(Task task) {
        businessValidationService.validateTaskNotArchived(task);
    }

    public void validateColumnHasNoTasks(Long columnId) {
        businessValidationService.validateColumnHasNoTasks(columnId);
    }

    public void validateBoardNameUnique(String name, Long serverId, Long excludeBoardId) {
        businessValidationService.validateBoardNameUnique(name, serverId, excludeBoardId);
    }

    public void validateLabelNameUnique(String name, Long boardId, Long excludeLabelId) {
        businessValidationService.validateLabelNameUnique(name, boardId, excludeLabelId);
    }

    public void validateNotificationBelongsToUser(Notification notification, Long userId) {
        if (!notification.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("This notification does not belong to you");
        }
    }

    public boolean permissionBelongsToServer(Permission permission, Long serverId) {
        return isPermissionInServer(permission, serverId);
    }

    private boolean isPermissionInServer(Permission permission, Long serverId) {
        String scopeType = permission.getScopeType();
        Long scopeId = permission.getScopeId();

        if ("SERVER".equalsIgnoreCase(scopeType)) {
            return serverId.equals(scopeId);
        }

        if ("BOARD".equalsIgnoreCase(scopeType)) {
            return boardService.findByIdAndServerId(scopeId, serverId).isPresent();
        }

        return false;
    }
}
