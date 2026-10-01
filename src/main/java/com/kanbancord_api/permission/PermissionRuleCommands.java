package com.kanbancord_api.permission;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, changing and deleting permission rules. Every change is checked by
 * {@link PermissionEscalationGuardService} against the rules as they would be after it.
 */
@Service
@Transactional
public class PermissionRuleCommands {

    private final PermissionService permissionService;
    private final KanbanPermissionService kanbanPermissionService;
    private final PermissionEscalationGuardService permissionEscalationGuardService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final ServerFeatureService features;

    public PermissionRuleCommands(
            PermissionService permissionService,
            KanbanPermissionService kanbanPermissionService,
            PermissionEscalationGuardService permissionEscalationGuardService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            ServerFeatureService features) {
        this.permissionService = permissionService;
        this.kanbanPermissionService = kanbanPermissionService;
        this.permissionEscalationGuardService = permissionEscalationGuardService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.features = features;
    }

    public PermissionResponse create(Long serverId, Long actorUserId, PermissionRequest request) {
        features.require(serverId, Feature.PERMISSIONS);
        authorizer.requireUserInServer(actorUserId, serverId);
        KanbanPermission kanbanPermission = validateRequest(serverId, request);

        Permission permission = new Permission();
        applyRequest(permission, request, kanbanPermission);
        permissionEscalationGuardService.validateChange(actorUserId, serverId, null, permission);

        PermissionResponse created = PermissionResponse.from(permissionService.create(permission));
        events.publishEvent(DomainEvent.created(EventType.PERMISSION_CREATED, serverId, boardIdOf(created),
                created.getId(), actorUserId, created));
        return created;
    }

    public PermissionResponse update(Long serverId, Long permissionId, Long actorUserId, PermissionRequest request) {
        features.require(serverId, Feature.PERMISSIONS);
        authorizer.requireUserInServer(actorUserId, serverId);
        Permission permission = requireMutableRule(serverId, permissionId, "modified");
        KanbanPermission kanbanPermission = validateRequest(serverId, request);

        Permission proposed = copyOf(permission);
        applyRequest(proposed, request, kanbanPermission);
        permissionEscalationGuardService.validateChange(actorUserId, serverId, copyOf(permission), proposed);

        PermissionResponse before = PermissionResponse.from(permission);
        applyRequest(permission, request, kanbanPermission);
        PermissionResponse after = PermissionResponse.from(permissionService.update(permission));
        events.publishEvent(DomainEvent.changed(EventType.PERMISSION_UPDATED, serverId, boardIdOf(after),
                permissionId, actorUserId, before, after));
        return after;
    }

    public PermissionResponse setState(Long serverId, Long permissionId, Long actorUserId, String state) {
        features.require(serverId, Feature.PERMISSIONS);
        authorizer.requireUserInServer(actorUserId, serverId);
        Permission permission = requireMutableRule(serverId, permissionId, "modified");
        if (state == null || (!state.equals("ALLOW") && !state.equals("DENY"))) {
            throw new BadRequestException("State must be ALLOW or DENY");
        }
        refuseDenyingAdministrators(permission.getKanbanPermission().getKey(), state);

        Permission proposed = copyOf(permission);
        proposed.setState(state);
        permissionEscalationGuardService.validateChange(actorUserId, serverId, copyOf(permission), proposed);

        PermissionResponse before = PermissionResponse.from(permission);
        permission.setState(state);
        PermissionResponse after = PermissionResponse.from(permissionService.update(permission));
        events.publishEvent(DomainEvent.changed(EventType.PERMISSION_UPDATED, serverId, boardIdOf(after),
                permissionId, actorUserId, before, after));
        return after;
    }

    public void delete(Long serverId, Long permissionId, Long actorUserId) {
        features.require(serverId, Feature.PERMISSIONS);
        authorizer.requireUserInServer(actorUserId, serverId);
        Permission permission = requireMutableRule(serverId, permissionId, "deleted");
        permissionEscalationGuardService.validateChange(actorUserId, serverId, copyOf(permission), null);

        PermissionResponse before = PermissionResponse.from(permission);
        permissionService.deleteById(permission.getId());
        events.publishEvent(DomainEvent.deleted(EventType.PERMISSION_DELETED, serverId, boardIdOf(before),
                permissionId, actorUserId, before));
    }

    private Permission requireMutableRule(Long serverId, Long permissionId, String verb) {
        Permission permission = resourceValidator.requirePermissionInServer(permissionId, serverId);
        if (Boolean.TRUE.equals(permission.getIsImmutable())) {
            throw new IllegalStateException("Immutable permissions cannot be " + verb);
        }
        return permission;
    }

    private KanbanPermission validateRequest(Long serverId, PermissionRequest request) {
        resourceValidator.validatePermissionScopeBelongsToServer(request.getScopeType(), request.getScopeId(),
                serverId);
        resourceValidator.validatePermissionSubjectBelongsToServer(request.getSubjectType(), request.getSubjectId(),
                serverId);
        KanbanPermission kanbanPermission = kanbanPermissionService.findById(request.getKanbanPermissionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "KanbanPermission", "permissionId", request.getKanbanPermissionId()));
        validateScopeApplicability(request.getScopeType(), kanbanPermission.getKey());
        refuseDenyingAdministrators(kanbanPermission.getKey(), request.getState());
        return kanbanPermission;
    }

    /**
     * Administrator can be given by a rule, never taken: administrators and the server owner may always
     * do everything, so the server can never lock out the people who run it.
     */
    private static void refuseDenyingAdministrators(String key, String state) {
        if (PermissionResolver.ADMIN_KEY.equals(key) && !"ALLOW".equals(state)) {
            throw new BadRequestException("Administrator cannot be denied; it can only be given");
        }
    }

    private static void validateScopeApplicability(String scopeType, String kanbanPermissionKey) {
        KanbanPermissionCatalog catalog = KanbanPermissionCatalog.fromKey(kanbanPermissionKey)
                .orElseThrow(() -> new BadRequestException("Unknown kanban permission key: " + kanbanPermissionKey));

        if ("SERVER".equalsIgnoreCase(scopeType) && !catalog.isServerScopeAllowed()) {
            throw new BadRequestException("Permission key " + kanbanPermissionKey + " does not support SERVER scope");
        }
        if ("BOARD".equalsIgnoreCase(scopeType) && !catalog.isBoardScopeAllowed()) {
            throw new BadRequestException("Permission key " + kanbanPermissionKey + " does not support BOARD scope");
        }
    }

    private static void applyRequest(Permission permission, PermissionRequest request,
            KanbanPermission kanbanPermission) {
        permission.setScopeType(request.getScopeType());
        permission.setScopeId(request.getScopeId());
        permission.setSubjectType(request.getSubjectType());
        permission.setSubjectId(request.getSubjectId());
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(request.getState());
        permission.setPriority(request.getPriority());
        // Immutable rules are system-owned (seeded defaults); clients can never create or keep them.
        permission.setIsImmutable(false);
    }

    /** A detached copy, so the guard can compare the stored rule with the proposed one. */
    private static Permission copyOf(Permission source) {
        Permission copy = new Permission();
        copy.setId(source.getId());
        copy.setScopeType(source.getScopeType());
        copy.setScopeId(source.getScopeId());
        copy.setSubjectType(source.getSubjectType());
        copy.setSubjectId(source.getSubjectId());
        copy.setKanbanPermission(source.getKanbanPermission());
        copy.setState(source.getState());
        copy.setPriority(source.getPriority());
        copy.setIsImmutable(source.getIsImmutable());
        return copy;
    }

    /** Board rules are board events (announced on the board and filtered by who can see it). */
    private static Long boardIdOf(PermissionResponse rule) {
        return "BOARD".equalsIgnoreCase(rule.getScopeType()) ? rule.getScopeId() : null;
    }
}
