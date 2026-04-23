package com.kanbancord_api.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.permission.PermissionRank;
import com.kanbancord_api.repository.PermissionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PermissionEscalationGuardService {

    private static final String SUBJECT_USER = "USER";
    private static final String SUBJECT_ROLE = "ROLE";
    private static final String STATE_ALLOW = "ALLOW";
    private static final String STATE_DENY = "DENY";
    private static final String PERMISSION_ADMIN = "ADMIN";
    private static final String PERMISSION_MANAGE_SERVER_PERMISSIONS = "MANAGE_SERVER_PERMISSIONS";

    private final PermissionEvaluationService permissionEvaluationService;
    private final PermissionRepository permissionRepository;
    private final ResourceValidator resourceValidator;
    private final ServerMemberService serverMemberService;
    private final MemberRoleService memberRoleService;

    public PermissionEscalationGuardService(
            PermissionEvaluationService permissionEvaluationService,
            PermissionRepository permissionRepository,
            ResourceValidator resourceValidator,
            ServerMemberService serverMemberService,
            MemberRoleService memberRoleService) {
        this.permissionEvaluationService = permissionEvaluationService;
        this.permissionRepository = permissionRepository;
        this.resourceValidator = resourceValidator;
        this.serverMemberService = serverMemberService;
        this.memberRoleService = memberRoleService;
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
                null,
                false);
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
                existingPermission.getId(),
                false);
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
                existingPermission.getId(),
                false);
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
                existingPermission.getId(),
                true);
    }

    private void validateModification(
            Long actorUserId,
            Long serverId,
            String subjectType,
            Long subjectId,
            String kanbanPermissionKey,
            String newState,
            Long excludedPermissionId,
            boolean deleteOperation) {

        // When the actor is modifying their own USER permission entry, calculate their
        // rank
        // as if that entry were already gone. This lets someone recover from an
        // accidental
        // self-deny without being permanently locked out.
        boolean actorIsSubject = SUBJECT_USER.equalsIgnoreCase(subjectType) && actorUserId.equals(subjectId);
        PermissionRank actorRank = permissionEvaluationService.calculateEffectiveRank(
                serverId, actorUserId, actorIsSubject ? excludedPermissionId : null);

        PermissionRank permissionRank = KanbanPermissionCatalog.fromKey(kanbanPermissionKey)
                .map(KanbanPermissionCatalog::getRank)
                .orElseThrow(() -> new BadRequestException("Unknown kanban permission key: " + kanbanPermissionKey));

        if (actorRank != PermissionRank.ADMIN) {
            // Must have management-level permission to mutate rules unless actor is ADMIN.
            if (actorRank.getWeight() < PermissionRank.SERVER_MANAGE.getWeight()) {
                throw new AccessDeniedException("You need server-management permissions to modify permission rules");
            }

            if (!actorRank.canModify(permissionRank)) {
                throw new AccessDeniedException("You cannot modify permission " + kanbanPermissionKey
                        + " because its rank is equal to or higher than yours");
            }

            PermissionRank targetRank = calculateTargetRank(subjectType, subjectId, serverId, excludedPermissionId);
            if ((SUBJECT_USER.equalsIgnoreCase(subjectType) || SUBJECT_ROLE.equalsIgnoreCase(subjectType))
                    && !actorRank.canModify(targetRank)) {
                throw new AccessDeniedException("You cannot modify permissions for a target with equal or higher rank");
            }
        }

        if (isCriticalPermission(kanbanPermissionKey)
                && isDenyState(newState)
                && isActorAffectedByTarget(actorUserId, serverId, subjectType, subjectId)) {
            validatePostMutationActorControl(
                    actorUserId,
                    serverId,
                    subjectType,
                    kanbanPermissionKey,
                    excludedPermissionId,
                    deleteOperation);
        }
    }

    private void validatePostMutationActorControl(
            Long actorUserId,
            Long serverId,
            String targetSubjectType,
            String kanbanPermissionKey,
            Long excludedPermissionId,
            boolean deleteOperation) {

        PermissionEvaluationService.Decision postAdminDecision = permissionEvaluationService
                .resolve(serverId, null, actorUserId, PERMISSION_ADMIN, excludedPermissionId);

        boolean addsDenyRule = !deleteOperation;
        boolean stillAdmin = postAdminDecision.allowed();
        if (addsDenyRule && PERMISSION_ADMIN.equals(kanbanPermissionKey)) {
            stillAdmin = remainsAllowedAfterAddingDeny(postAdminDecision, targetSubjectType);
        }

        boolean stillManage;
        if (stillAdmin) {
            stillManage = true;
        } else {
            PermissionEvaluationService.Decision postManageWithoutAdmin = permissionEvaluationService
                    .resolveWithoutAdminGrant(
                            serverId,
                            null,
                            actorUserId,
                            PERMISSION_MANAGE_SERVER_PERMISSIONS,
                            excludedPermissionId);

            stillManage = postManageWithoutAdmin.allowed();
            if (addsDenyRule && PERMISSION_MANAGE_SERVER_PERMISSIONS.equals(kanbanPermissionKey)) {
                stillManage = remainsAllowedAfterAddingDeny(postManageWithoutAdmin, targetSubjectType);
            }
        }

        if (!stillAdmin && !stillManage) {
            throw new BadRequestException(
                    "This action would remove your ability to manage permissions and lock you out");
        }
    }

    private boolean remainsAllowedAfterAddingDeny(
            PermissionEvaluationService.Decision baseDecision,
            String targetSubjectType) {
        if (!baseDecision.allowed()) {
            return false;
        }

        String sourceTier = baseDecision.sourceTier();
        if (SUBJECT_USER.equalsIgnoreCase(targetSubjectType)) {
            return SUBJECT_USER.equals(sourceTier);
        }

        if (SUBJECT_ROLE.equalsIgnoreCase(targetSubjectType)) {
            return SUBJECT_USER.equals(sourceTier) || SUBJECT_ROLE.equals(sourceTier);
        }

        return false;
    }

    private boolean isCriticalPermission(String kanbanPermissionKey) {
        return PERMISSION_ADMIN.equals(kanbanPermissionKey)
                || PERMISSION_MANAGE_SERVER_PERMISSIONS.equals(kanbanPermissionKey);
    }

    private boolean isDenyState(String state) {
        return state != null && STATE_DENY.equals(state.toUpperCase(Locale.ROOT));
    }

    private boolean isActorAffectedByTarget(
            Long actorUserId,
            Long serverId,
            String subjectType,
            Long subjectId) {
        if (SUBJECT_USER.equalsIgnoreCase(subjectType)) {
            return actorUserId.equals(subjectId);
        }

        if (!SUBJECT_ROLE.equalsIgnoreCase(subjectType)) {
            return false;
        }

        Optional<ServerMember> actorMember = serverMemberService.findByServerIdAndUserId(serverId, actorUserId);
        if (actorMember.isEmpty()) {
            return false;
        }

        return memberRoleService.findByServerMemberIdAndRoleId(actorMember.get().getId(), subjectId).isPresent();
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