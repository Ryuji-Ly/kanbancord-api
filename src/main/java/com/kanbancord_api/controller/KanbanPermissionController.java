package com.kanbancord_api.controller;

import com.kanbancord_api.dto.KanbanPermissionResponse;
import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.KanbanPermissionService;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only view of the global permission catalog. The catalog is defined by
 * {@link com.kanbancord_api.permission.KanbanPermissionCatalog} and seeded by
 * migrations, so it is never mutated through the API.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/permissions/catalog")
@Validated
public class KanbanPermissionController {

    private final KanbanPermissionService kanbanPermissionService;
    private final AccessValidator accessValidator;

    public KanbanPermissionController(KanbanPermissionService kanbanPermissionService,
            AccessValidator accessValidator) {
        this.kanbanPermissionService = kanbanPermissionService;
        this.accessValidator = accessValidator;
    }

    @GetMapping
    public ResponseEntity<List<KanbanPermissionResponse>> getKanbanPermissions(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestParam(required = false) String category) {

        accessValidator.requireUserInServer(userId, serverId);

        List<KanbanPermission> permissions = category == null
                ? kanbanPermissionService.findAll()
                : kanbanPermissionService.findByCategory(category);

        List<KanbanPermissionResponse> responses = permissions.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    private KanbanPermissionResponse toResponse(KanbanPermission permission) {
        KanbanPermissionResponse response = new KanbanPermissionResponse();
        response.setPermissionId(permission.getPermissionId());
        response.setKey(permission.getKey());
        response.setName(permission.getName());
        response.setDescription(permission.getDescription());
        response.setCategory(permission.getCategory());
        return response;
    }
}
