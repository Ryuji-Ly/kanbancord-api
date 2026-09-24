package com.kanbancord_api.controller;

import com.kanbancord_api.dto.PermissionRequest;
import com.kanbancord_api.dto.PermissionResponse;
import com.kanbancord_api.dto.PermissionDecisionResponse;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.realtime.RealtimeEventPublisher;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.KanbanPermissionService;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.PermissionEscalationGuardService;
import com.kanbancord_api.service.PermissionService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/permissions")
@Validated
public class PermissionController {

    private static final String VIEW_BOARD = "VIEW_BOARD";

    private final PermissionService permissionService;
    private final KanbanPermissionService kanbanPermissionService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final PermissionEvaluationService permissionEvaluationService;
    private final PermissionEscalationGuardService permissionEscalationGuardService;
    private final RealtimeEventPublisher realtimeEventPublisher;

    public PermissionController(
            PermissionService permissionService,
            KanbanPermissionService kanbanPermissionService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            PermissionEvaluationService permissionEvaluationService,
            PermissionEscalationGuardService permissionEscalationGuardService,
            RealtimeEventPublisher realtimeEventPublisher) {
        this.permissionService = permissionService;
        this.kanbanPermissionService = kanbanPermissionService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.permissionEvaluationService = permissionEvaluationService;
        this.permissionEscalationGuardService = permissionEscalationGuardService;
        this.realtimeEventPublisher = realtimeEventPublisher;
    }

    @PostMapping
    public ResponseEntity<PermissionResponse> createPermission(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @Valid @RequestBody PermissionRequest request) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePermissionScopeBelongsToServer(request.getScopeType(), request.getScopeId(),
                serverId);
        resourceValidator.validatePermissionSubjectBelongsToServer(request.getSubjectType(), request.getSubjectId(),
                serverId);

        KanbanPermission kanbanPermission = kanbanPermissionService.findById(request.getKanbanPermissionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "KanbanPermission", "permissionId", request.getKanbanPermissionId()));
        validateScopeApplicability(request.getScopeType(), kanbanPermission.getKey());

        Permission permission = new Permission();
        applyRequest(permission, request, kanbanPermission);
        permissionEscalationGuardService.validateChange(userId, serverId, null, permission);

        Permission created = permissionService.create(permission);
        PermissionResponse response = toResponse(created);
        publishPermissionEvent(serverId, userId, "PERMISSION_CREATED", response);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PermissionResponse>> getPermissions(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) Long scopeId) {

        accessValidator.requireUserInServer(userId, serverId);

        List<Permission> permissions;
        if (scopeType != null && scopeId != null) {
            resourceValidator.validatePermissionScopeBelongsToServer(scopeType, scopeId, serverId);
            if (isBoardScope(scopeType)) {
                accessValidator.requireBoardPermission(userId, serverId, scopeId, VIEW_BOARD);
            }
            permissions = permissionService.findByScope(scopeType, scopeId);
        } else {
            permissions = permissionService.findAllInServer(serverId);
        }

        List<PermissionResponse> responses = withoutHiddenBoardRules(serverId, userId, permissions).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{permissionId}")
    public ResponseEntity<PermissionResponse> getPermissionById(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @CurrentUser Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        Permission permission = resourceValidator.requirePermissionInServer(permissionId, serverId);
        if (isBoardScope(permission.getScopeType())) {
            accessValidator.requireBoardPermission(userId, serverId, permission.getScopeId(), VIEW_BOARD);
        }
        return ResponseEntity.ok(toResponse(permission));
    }

    /**
     * Board rules reveal that a board exists and who may see it, so they are only listed to users
     * who can view that board.
     */
    private List<Permission> withoutHiddenBoardRules(Long serverId, Long userId, List<Permission> permissions) {
        Set<Long> boardIds = permissions.stream()
                .filter(permission -> isBoardScope(permission.getScopeType()))
                .map(Permission::getScopeId)
                .collect(Collectors.toSet());
        Set<Long> visibleBoardIds = permissionEvaluationService.filterAllowedBoards(serverId, boardIds, userId,
                VIEW_BOARD);
        return permissions.stream()
                .filter(permission -> !isBoardScope(permission.getScopeType())
                        || visibleBoardIds.contains(permission.getScopeId()))
                .toList();
    }

    private static boolean isBoardScope(String scopeType) {
        return "BOARD".equalsIgnoreCase(scopeType);
    }

    @PutMapping("/{permissionId}")
    public ResponseEntity<PermissionResponse> updatePermission(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @CurrentUser Long userId,
            @Valid @RequestBody PermissionRequest request) {

        accessValidator.requireUserInServer(userId, serverId);

        Permission permission = resourceValidator.requirePermissionInServer(permissionId, serverId);
        if (permission.getIsImmutable() != null && permission.getIsImmutable()) {
            throw new IllegalStateException("Immutable permissions cannot be modified");
        }

        resourceValidator.validatePermissionScopeBelongsToServer(request.getScopeType(), request.getScopeId(),
                serverId);
        resourceValidator.validatePermissionSubjectBelongsToServer(request.getSubjectType(), request.getSubjectId(),
                serverId);

        KanbanPermission kanbanPermission = kanbanPermissionService.findById(request.getKanbanPermissionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "KanbanPermission", "permissionId", request.getKanbanPermissionId()));
        validateScopeApplicability(request.getScopeType(), kanbanPermission.getKey());

        Permission after = copyOf(permission);
        applyRequest(after, request, kanbanPermission);
        permissionEscalationGuardService.validateChange(userId, serverId, copyOf(permission), after);

        applyRequest(permission, request, kanbanPermission);

        Permission updated = permissionService.update(permission);
        PermissionResponse response = toResponse(updated);
        publishPermissionEvent(serverId, userId, "PERMISSION_UPDATED", response);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{permissionId}/state")
    public ResponseEntity<Void> patchPermissionState(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @CurrentUser Long userId,
            @RequestBody java.util.Map<String, String> body) {

        accessValidator.requireUserInServer(userId, serverId);

        Permission permission = resourceValidator.requirePermissionInServer(permissionId, serverId);
        if (permission.getIsImmutable() != null && permission.getIsImmutable()) {
            throw new BadRequestException("Immutable permissions cannot be modified");
        }

        String newState = body.get("state");
        if (newState == null || (!newState.equals("ALLOW") && !newState.equals("DENY"))) {
            throw new BadRequestException("State must be ALLOW or DENY");
        }

        Permission after = copyOf(permission);
        after.setState(newState);
        permissionEscalationGuardService.validateChange(userId, serverId, copyOf(permission), after);

        permission.setState(newState);
        Permission updated = permissionService.update(permission);
        publishPermissionEvent(serverId, userId, "PERMISSION_UPDATED", toResponse(updated));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<Void> deletePermission(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @CurrentUser Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        Permission permission = resourceValidator.requirePermissionInServer(permissionId, serverId);
        if (permission.getIsImmutable() != null && permission.getIsImmutable()) {
            throw new IllegalStateException("Immutable permissions cannot be deleted");
        }

        permissionEscalationGuardService.validateChange(userId, serverId, copyOf(permission), null);

        PermissionResponse response = toResponse(permission);
        permissionService.deleteById(permission.getId());
        publishPermissionEvent(serverId, userId, "PERMISSION_DELETED", response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/evaluate")
    public ResponseEntity<PermissionDecisionResponse> evaluatePermission(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam String permissionKey,
            @RequestParam(required = false) Long boardId) {

        Long subjectUserId = authorizeEvaluation(userId, serverId, targetUserId, boardId);

        PermissionEvaluationService.Decision decision = permissionEvaluationService.resolve(
                serverId,
                boardId,
                subjectUserId,
                permissionKey);

        return ResponseEntity.ok(toDecisionResponse(decision));
    }

    @GetMapping("/evaluate-batch")
    public ResponseEntity<Map<String, PermissionDecisionResponse>> evaluatePermissions(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam List<String> permissionKey,
            @RequestParam(required = false) Long boardId) {

        Long subjectUserId = authorizeEvaluation(userId, serverId, targetUserId, boardId);

        Map<String, PermissionDecisionResponse> responses = new LinkedHashMap<>();
        permissionEvaluationService.resolveAll(serverId, boardId, subjectUserId, permissionKey)
                .forEach((key, decision) -> responses.put(key, toDecisionResponse(decision)));

        return ResponseEntity.ok(responses);
    }

    /**
     * Anyone may evaluate their own permissions; evaluating another member's requires
     * MANAGE_SERVER_PERMISSIONS. Returns the user whose permissions are evaluated.
     */
    private Long authorizeEvaluation(Long userId, Long serverId, Long targetUserId, Long boardId) {
        Long subjectUserId = targetUserId != null ? targetUserId : userId;
        if (subjectUserId.equals(userId)) {
            accessValidator.requireUserInServer(userId, serverId);
        } else {
            accessValidator.requireServerPermission(userId, serverId, "MANAGE_SERVER_PERMISSIONS");
            resourceValidator.validatePermissionSubjectBelongsToServer("USER", subjectUserId, serverId);
        }
        if (boardId != null) {
            resourceValidator.requireBoardInServer(boardId, serverId);
        }
        return subjectUserId;
    }

    private static PermissionDecisionResponse toDecisionResponse(PermissionEvaluationService.Decision decision) {
        PermissionDecisionResponse response = new PermissionDecisionResponse();
        response.setAllowed(decision.allowed());
        response.setSourceTier(decision.sourceTier());
        response.setSourceScopeType(decision.sourceScopeType());
        response.setSourceScopeId(decision.sourceScopeId());
        response.setSourcePermissionId(decision.sourcePermissionId());
        return response;
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

    private void validateScopeApplicability(String scopeType, String kanbanPermissionKey) {
        KanbanPermissionCatalog catalog = KanbanPermissionCatalog.fromKey(kanbanPermissionKey)
                .orElseThrow(() -> new BadRequestException("Unknown kanban permission key: " + kanbanPermissionKey));

        if ("SERVER".equalsIgnoreCase(scopeType) && !catalog.isServerScopeAllowed()) {
            throw new BadRequestException("Permission key " + kanbanPermissionKey + " does not support SERVER scope");
        }

        if ("BOARD".equalsIgnoreCase(scopeType) && !catalog.isBoardScopeAllowed()) {
            throw new BadRequestException("Permission key " + kanbanPermissionKey + " does not support BOARD scope");
        }
    }

    private PermissionResponse toResponse(Permission permission) {
        PermissionResponse response = new PermissionResponse();
        response.setId(permission.getId());
        response.setScopeType(permission.getScopeType());
        response.setScopeId(permission.getScopeId());
        response.setSubjectType(permission.getSubjectType());
        response.setSubjectId(permission.getSubjectId());

        if (permission.getKanbanPermission() != null) {
            response.setKanbanPermissionId(permission.getKanbanPermission().getPermissionId());
            response.setKanbanPermissionKey(permission.getKanbanPermission().getKey());
        }

        response.setState(permission.getState());
        response.setPriority(permission.getPriority());
        response.setIsImmutable(permission.getIsImmutable());
        response.setCreatedAt(permission.getCreatedAt());
        response.setUpdatedAt(permission.getUpdatedAt());
        return response;
    }

    private void publishPermissionEvent(Long serverId, Long actorUserId, String eventType,
            PermissionResponse response) {
        if (response == null) {
            return;
        }

        if ("BOARD".equalsIgnoreCase(response.getScopeType()) && response.getScopeId() != null) {
            realtimeEventPublisher.publishToServerAndBoardTopics(
                    serverId,
                    response.getScopeId(),
                    realtimeEventPublisher.newEvent(
                            eventType,
                            "BOARD",
                            serverId,
                            response.getScopeId(),
                            "PERMISSION",
                            response.getId(),
                            actorUserId,
                            response));
            return;
        }

        realtimeEventPublisher.publishToServerTopic(
                serverId,
                realtimeEventPublisher.newEvent(
                        eventType,
                        "SERVER",
                        serverId,
                        null,
                        "PERMISSION",
                        response.getId(),
                        actorUserId,
                        response));
    }
}
