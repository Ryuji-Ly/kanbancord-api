package com.kanbancord_api.controller;

import com.kanbancord_api.dto.PermissionRequest;
import com.kanbancord_api.dto.PermissionResponse;
import com.kanbancord_api.dto.PermissionDecisionResponse;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.KanbanPermissionService;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.PermissionService;
import com.kanbancord_api.service.ResourceValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/permissions")
@Validated
public class PermissionController {

    private final PermissionService permissionService;
    private final KanbanPermissionService kanbanPermissionService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final PermissionEvaluationService permissionEvaluationService;

    public PermissionController(
            PermissionService permissionService,
            KanbanPermissionService kanbanPermissionService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            PermissionEvaluationService permissionEvaluationService) {
        this.permissionService = permissionService;
        this.kanbanPermissionService = kanbanPermissionService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.permissionEvaluationService = permissionEvaluationService;
    }

    @PostMapping
    public ResponseEntity<PermissionResponse> createPermission(
            @PathVariable Long serverId,
            @RequestParam Long userId,
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
        permission.setScopeType(request.getScopeType());
        permission.setScopeId(request.getScopeId());
        permission.setSubjectType(request.getSubjectType());
        permission.setSubjectId(request.getSubjectId());
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(request.getState());
        permission.setPriority(request.getPriority());
        permission.setIsImmutable(request.getIsImmutable() != null && request.getIsImmutable());

        Permission created = permissionService.create(permission);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<PermissionResponse>> getPermissions(
            @PathVariable Long serverId,
            @RequestParam Long userId,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) Long scopeId) {

        accessValidator.requireUserInServer(userId, serverId);

        List<Permission> permissions;
        if (scopeType != null && scopeId != null) {
            resourceValidator.validatePermissionScopeBelongsToServer(scopeType, scopeId, serverId);
            permissions = permissionService.findByScope(scopeType, scopeId);
        } else {
            permissions = permissionService.findAll().stream()
                    .filter(permission -> resourceValidator.permissionBelongsToServer(permission, serverId))
                    .collect(Collectors.toList());
        }

        List<PermissionResponse> responses = permissions.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{permissionId}")
    public ResponseEntity<PermissionResponse> getPermissionById(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        Permission permission = resourceValidator.requirePermissionInServer(permissionId, serverId);
        return ResponseEntity.ok(toResponse(permission));
    }

    @PutMapping("/{permissionId}")
    public ResponseEntity<PermissionResponse> updatePermission(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @RequestParam Long userId,
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

        permission.setScopeType(request.getScopeType());
        permission.setScopeId(request.getScopeId());
        permission.setSubjectType(request.getSubjectType());
        permission.setSubjectId(request.getSubjectId());
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(request.getState());
        permission.setPriority(request.getPriority());
        permission.setIsImmutable(request.getIsImmutable() != null && request.getIsImmutable());

        Permission updated = permissionService.update(permission);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<Void> deletePermission(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        Permission permission = resourceValidator.requirePermissionInServer(permissionId, serverId);
        if (permission.getIsImmutable() != null && permission.getIsImmutable()) {
            throw new IllegalStateException("Immutable permissions cannot be deleted");
        }

        permissionService.deleteById(permission.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/evaluate")
    public ResponseEntity<PermissionDecisionResponse> evaluatePermission(
            @PathVariable Long serverId,
            @RequestParam Long userId,
            @RequestParam Long targetUserId,
            @RequestParam String permissionKey,
            @RequestParam(required = false) Long boardId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", targetUserId, serverId);

        PermissionEvaluationService.Decision decision = permissionEvaluationService.resolve(
                serverId,
                boardId,
                targetUserId,
                permissionKey);

        PermissionDecisionResponse response = new PermissionDecisionResponse();
        response.setAllowed(decision.allowed());
        response.setSourceTier(decision.sourceTier());
        response.setSourceScopeType(decision.sourceScopeType());
        response.setSourceScopeId(decision.sourceScopeId());
        response.setSourcePermissionId(decision.sourcePermissionId());
        return ResponseEntity.ok(response);
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
        response.setKanbanPermissionId(permission.getKanbanPermission().getPermissionId());
        response.setKanbanPermissionKey(permission.getKanbanPermission().getKey());
        response.setState(permission.getState());
        response.setPriority(permission.getPriority());
        response.setIsImmutable(permission.getIsImmutable());
        response.setCreatedAt(permission.getCreatedAt());
        response.setUpdatedAt(permission.getUpdatedAt());
        return response;
    }
}
