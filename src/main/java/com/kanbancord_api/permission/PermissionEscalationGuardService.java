package com.kanbancord_api.permission;

import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Decides whether an actor may create, change or delete a permission rule.
 *
 * <ul>
 * <li>Server-scope rules require server-management rank (MANAGE_SERVER_PERMISSIONS or ADMIN).</li>
 * <li>Board-scope rules require EDIT_BOARD_PERMISSIONS on that board, or server-management rank.</li>
 * <li>Unless ADMIN, an actor can only touch keys ranked below their own effective rank, and only
 * rules whose USER/ROLE subject ranks below theirs.</li>
 * <li>A change may not remove the actor's own ability to manage server permissions.</li>
 * </ul>
 * Changes are evaluated by applying them to an in-memory {@link PermissionSnapshot}, so the checks use
 * exactly the same resolution rules as enforcement.
 */
@Service
public class PermissionEscalationGuardService {

    private static final String SCOPE_BOARD = "BOARD";
    private static final String SUBJECT_USER = "USER";
    private static final String SUBJECT_ROLE = "ROLE";
    private static final String STATE_ALLOW = "ALLOW";
    private static final String MANAGE_SERVER_PERMISSIONS = "MANAGE_SERVER_PERMISSIONS";
    private static final String EDIT_BOARD_PERMISSIONS = "EDIT_BOARD_PERMISSIONS";

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

    /**
     * Validates replacing {@code before} with {@code after}. Pass {@code before = null} for a create and
     * {@code after = null} for a delete. {@code after} must not be persisted yet, and {@code before}
     * must be a detached copy of the stored rule (not the entity being edited).
     */
    public void validateChange(Long actorUserId, Long serverId, Permission before, Permission after) {
        Stream.of(before, after)
                .filter(rule -> rule != null)
                .forEach(rule -> authorizeRuleScope(actorUserId, serverId, rule, before));

        preventManagementLockout(actorUserId, serverId, before, after);
    }

    private void authorizeRuleScope(Long actorUserId, Long serverId, Permission rule, Permission before) {
        Long boardId = isBoardRule(rule) ? rule.getScopeId() : null;
        PermissionSnapshot snapshot = permissionEvaluationService.loadSnapshot(serverId, boardId, actorUserId);

        // Self-recovery: when editing a USER rule about themselves, judge the actor as if that rule were
        // already gone, so an accidental self-deny can always be undone.
        if (before != null && SUBJECT_USER.equals(before.getSubjectType())
                && actorUserId.equals(before.getSubjectId())) {
            snapshot = snapshot.withRuleChange(before, null);
        }

        PermissionRank actorRank = PermissionResolver.effectiveRank(snapshot);
        if (actorRank == PermissionRank.ADMIN) {
            return;
        }

        boolean managesServer = actorRank.getWeight() >= PermissionRank.SERVER_MANAGE.getWeight();
        if (boardId == null && !managesServer) {
            throw new AccessDeniedException("You need server-management permissions to modify server permission rules");
        }
        if (boardId != null && !managesServer
                && !PermissionResolver.resolve(snapshot, EDIT_BOARD_PERMISSIONS).allowed()) {
            throw new AccessDeniedException("You need EDIT_BOARD_PERMISSIONS on this board to modify its permission rules");
        }

        String key = rule.getKanbanPermission() != null ? rule.getKanbanPermission().getKey() : null;
        PermissionRank keyRank = KanbanPermissionCatalog.fromKey(key)
                .map(KanbanPermissionCatalog::getRank)
                .orElseThrow(() -> new BadRequestException("Unknown kanban permission key: " + key));
        if (!actorRank.canModify(keyRank)) {
            throw new AccessDeniedException("You cannot modify permission " + key
                    + " because its rank is equal to or higher than yours");
        }

        if (SUBJECT_USER.equals(rule.getSubjectType()) || SUBJECT_ROLE.equals(rule.getSubjectType())) {
            PermissionRank targetRank = subjectRank(serverId, boardId, rule, before);
            if (!actorRank.canModify(targetRank)) {
                throw new AccessDeniedException("You cannot modify permissions for a target with equal or higher rank");
            }
        }
    }

    private void preventManagementLockout(Long actorUserId, Long serverId, Permission before, Permission after) {
        // Server-management keys only exist at server scope, so board rules cannot lock anyone out.
        boolean touchesServerScope = Stream.of(before, after)
                .anyMatch(rule -> rule != null && !isBoardRule(rule));
        if (!touchesServerScope) {
            return;
        }

        PermissionSnapshot current = permissionEvaluationService.loadSnapshot(serverId, null, actorUserId);
        if (!canManageServer(current)) {
            return;
        }
        if (!canManageServer(current.withRuleChange(before, after))) {
            throw new BadRequestException("This action would remove your ability to manage permissions and lock you out");
        }
    }

    private static boolean canManageServer(PermissionSnapshot snapshot) {
        return PermissionResolver.resolve(snapshot, MANAGE_SERVER_PERMISSIONS).allowed();
    }

    private PermissionRank subjectRank(Long serverId, Long boardId, Permission rule, Permission before) {
        if (SUBJECT_USER.equals(rule.getSubjectType())) {
            PermissionSnapshot target = permissionEvaluationService.loadSnapshot(serverId, boardId, rule.getSubjectId());
            if (before != null) {
                target = target.withRuleChange(before, null);
            }
            return PermissionResolver.effectiveRank(target);
        }

        // A role's rank is the highest key it is explicitly allowed anywhere in this server.
        PermissionRank best = PermissionRank.READONLY;
        for (Permission permission : permissionRepository.findBySubjectTypeAndSubjectId(SUBJECT_ROLE, rule.getSubjectId())) {
            if (before != null && before.getId() != null && before.getId().equals(permission.getId())) {
                continue;
            }
            if (!resourceValidator.permissionBelongsToServer(permission, serverId)
                    || permission.getState() == null
                    || !STATE_ALLOW.equals(permission.getState().toUpperCase(Locale.ROOT))
                    || permission.getKanbanPermission() == null) {
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

    private static boolean isBoardRule(Permission rule) {
        return SCOPE_BOARD.equals(rule.getScopeType());
    }
}
