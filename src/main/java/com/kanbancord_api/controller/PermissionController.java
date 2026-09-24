package com.kanbancord_api.controller;

import com.kanbancord_api.command.PermissionRuleCommands;
import com.kanbancord_api.dto.PermissionRequest;
import com.kanbancord_api.dto.PermissionResponse;
import com.kanbancord_api.dto.PermissionDecisionResponse;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.PermissionEvaluationService;
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
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final PermissionEvaluationService permissionEvaluationService;
    private final PermissionRuleCommands permissionRuleCommands;

    public PermissionController(
            PermissionService permissionService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            PermissionEvaluationService permissionEvaluationService,
            PermissionRuleCommands permissionRuleCommands) {
        this.permissionService = permissionService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.permissionEvaluationService = permissionEvaluationService;
        this.permissionRuleCommands = permissionRuleCommands;
    }

    @PostMapping
    public ResponseEntity<PermissionResponse> createPermission(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @Valid @RequestBody PermissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(permissionRuleCommands.create(serverId, userId, request));
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
                .map(PermissionResponse::from)
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
        return ResponseEntity.ok(PermissionResponse.from(permission));
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
        return ResponseEntity.ok(permissionRuleCommands.update(serverId, permissionId, userId, request));
    }

    @PatchMapping("/{permissionId}/state")
    public ResponseEntity<Void> patchPermissionState(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @CurrentUser Long userId,
            @RequestBody Map<String, String> body) {
        permissionRuleCommands.setState(serverId, permissionId, userId, body.get("state"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<Void> deletePermission(
            @PathVariable Long serverId,
            @PathVariable Long permissionId,
            @CurrentUser Long userId) {
        permissionRuleCommands.delete(serverId, permissionId, userId);
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

        return ResponseEntity.ok(PermissionDecisionResponse.from(decision));
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
                .forEach((key, decision) -> responses.put(key, PermissionDecisionResponse.from(decision)));

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
}
