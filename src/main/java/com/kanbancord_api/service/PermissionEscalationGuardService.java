package com.kanbancord_api.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.permission.PermissionRank;
import com.kanbancord_api.repository.PermissionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class PermissionEscalationGuardService {

    private static final String SUBJECT_USER = "USER";
    private static final String SUBJECT_ROLE = "ROLE";
    private static final String STATE_ALLOW = "ALLOW";
    private static final String STATE_DENY = "DENY";

    private final PermissionEvaluationService permissionEvaluationService;
    private final PermissionRepository permissionRepository;
    private final ResourceValidator resourceValidator;

    public PermissionEscalationGuardService(
            PermissionEvaluationService permissionEvaluationService,
            PermissionRepository permissionRepository,
            ResourceValidator resourceValidator) {
        this.permissionEvaluationService = permissionEvaluationService;
        this.permissionRepository = permissionRepository;
        this.resourceValidator = resourceValidator;
    }

    public void validateCreate(
            Long actorUserId,
            Long serverId,
            String subjectType,
            Long subjectId,
            String kanbanPermissionKey,
            String newState) {
        validateModification(
                actorUserId,
                serverId,
                subjectType,
                subjectId,
                kanbanPermissionKey,
                newState,
                null);
    }

    public void validateUpdate(
            Long actorUserId,
            Long serverId,
            Permission existingPermission,
            String subjectType,
            Long subjectId,
            String kanbanPermissionKey,
            String newState) {
        validateModification(
                actorUserId,
                serverId,
                subjectType,
                subjectId,
                kanbanPermissionKey,
                newState,
                existingPermission.getId());
    }

    public void validatePatchState(
            Long actorUserId,
            Long serverId,
            Permission existingPermission,
            String newState) {
        if (newState.equalsIgnoreCase(existingPermission.getState())) {
            return;
        }

        validateModification(
                actorUserId,
                serverId,
                existingPermission.getSubjectType(),
                existingPermission.getSubjectId(),
                existingPermission.getKanbanPermission().getKey(),
                newState,
                existingPermission.getId());
    }

    public void validateDelete(
            Long actorUserId,
            Long serverId,
            Permission existingPermission) {
        validateModification(
                actorUserId,
                serverId,
                existingPermission.getSubjectType(),
                existingPermission.getSubjectId(),
                existingPermission.getKanbanPermission().getKey(),
                STATE_DENY,
                existingPermission.getId());
    }

    private void validateModification(
            Long actorUserId,
            Long serverId,
            String subjectType,
            Long subjectId,
            String kanbanPermissionKey,
            String newState,
            Long excludedPermissionId) {

        PermissionRank actorRank = permissionEvaluationService.calculateEffectiveRank(serverId, actorUserId);

        if (actorRank == PermissionRank.ADMIN) {
            return;
        }

        // Must have management-level permission to mutate rules unless actor is ADMIN.
        if (actorRank.getWeight() < PermissionRank.SERVER_MANAGE.getWeight()) {
            throw new AccessDeniedException("You need server-management permissions to modify permission rules");
        }

        PermissionRank permissionRank = KanbanPermissionCatalog.fromKey(kanbanPermissionKey)
                .map(KanbanPermissionCatalog::getRank)
                .orElseThrow(() -> new BadRequestException("Unknown kanban permission key: " + kanbanPermissionKey));

        if (!actorRank.canModify(permissionRank)) {
            throw new AccessDeniedException("You cannot modify permission " + kanbanPermissionKey
                    + " because its rank is equal to or higher than yours");
        }

        PermissionRank targetRank = calculateTargetRank(subjectType, subjectId, serverId, excludedPermissionId);
        if ((SUBJECT_USER.equalsIgnoreCase(subjectType) || SUBJECT_ROLE.equalsIgnoreCase(subjectType))
                && !actorRank.canModify(targetRank)) {
            throw new AccessDeniedException("You cannot modify permissions for a target with equal or higher rank");
        }

        boolean isSelfDeny = SUBJECT_USER.equalsIgnoreCase(subjectType)
                && actorUserId.equals(subjectId)
                && STATE_DENY.equals(newState.toUpperCase(Locale.ROOT));

        if (isSelfDeny && permissionRank.getWeight() >= PermissionRank.SERVER_MANAGE.getWeight()) {
            boolean stillAdmin = permissionEvaluationService
                    .resolve(serverId, null, actorUserId, "ADMIN", excludedPermissionId)
                    .allowed();
            boolean stillManage = permissionEvaluationService
                    .resolve(serverId, null, actorUserId, "MANAGE_SERVER_PERMISSIONS", excludedPermissionId)
                    .allowed();

            if (!stillAdmin && !stillManage) {
                throw new BadRequestException(
                        "This action would remove your ability to manage permissions and lock you out");
            }
        }
    }

    private PermissionRank calculateTargetRank(
            String subjectType,
            Long subjectId,
            Long serverId,
            Long excludedPermissionId) {
        if (SUBJECT_USER.equalsIgnoreCase(subjectType)) {
            return permissionEvaluationService.calculateEffectiveRank(serverId, subjectId, excludedPermissionId);
        }

        List<Permission> permissions = permissionRepository.findBySubjectTypeAndSubjectId(subjectType, subjectId)
                .stream()
                .filter(permission -> resourceValidator.permissionBelongsToServer(permission, serverId))
                .filter(permission -> excludedPermissionId == null || !excludedPermissionId.equals(permission.getId()))
                .filter(permission -> permission.getState() != null
                        && STATE_ALLOW.equals(permission.getState().toUpperCase(Locale.ROOT)))
                .toList();

        PermissionRank best = PermissionRank.READONLY;
        for (Permission permission : permissions) {
            if (permission.getKanbanPermission() == null || permission.getKanbanPermission().getKey() == null) {
                continue;
            }

            PermissionRank rank = KanbanPermissionCatalog.fromKey(permission.getKanbanPermission().getKey())
                    .map(KanbanPermissionCatalog::getRank)
                    .orElse(PermissionRank.READONLY);

            if (rank.getWeight() > best.getWeight()) {
                best = rank;
            }
        }

        return best;
    }
}