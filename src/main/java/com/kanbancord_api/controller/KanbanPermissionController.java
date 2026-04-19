package com.kanbancord_api.controller;

import com.kanbancord_api.dto.KanbanPermissionRequest;
import com.kanbancord_api.dto.KanbanPermissionResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.KanbanPermissionService;
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

    @PostMapping
    public ResponseEntity<KanbanPermissionResponse> createKanbanPermission(
            @PathVariable Long serverId,
            @RequestParam Long userId,
            @Valid @RequestBody KanbanPermissionRequest request) {

        accessValidator.requireUserInServer(userId, serverId);

        KanbanPermission permission = new KanbanPermission();
        permission.setKey(request.getKey());
        permission.setName(request.getName());
        permission.setDescription(request.getDescription());
        permission.setCategory(request.getCategory());

        KanbanPermission created = kanbanPermissionService.create(permission);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<KanbanPermissionResponse>> getKanbanPermissions(
            @PathVariable Long serverId,
            @RequestParam Long userId,
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

    @GetMapping("/{permissionId}")
    public ResponseEntity<KanbanPermissionResponse> getKanbanPermissionById(
            @PathVariable Long serverId,
            @PathVariable Integer permissionId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        KanbanPermission permission = kanbanPermissionService.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanPermission", "permissionId", permissionId));

        return ResponseEntity.ok(toResponse(permission));
    }

    @PutMapping("/{permissionId}")
    public ResponseEntity<KanbanPermissionResponse> updateKanbanPermission(
            @PathVariable Long serverId,
            @PathVariable Integer permissionId,
            @RequestParam Long userId,
            @Valid @RequestBody KanbanPermissionRequest request) {

        accessValidator.requireUserInServer(userId, serverId);

        KanbanPermission permission = kanbanPermissionService.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanPermission", "permissionId", permissionId));

        permission.setKey(request.getKey());
        permission.setName(request.getName());
        permission.setDescription(request.getDescription());
        permission.setCategory(request.getCategory());

        KanbanPermission updated = kanbanPermissionService.update(permission);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<Void> deleteKanbanPermission(
            @PathVariable Long serverId,
            @PathVariable Integer permissionId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        KanbanPermission permission = kanbanPermissionService.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("KanbanPermission", "permissionId", permissionId));

        kanbanPermissionService.deleteById(permission.getPermissionId());
        return ResponseEntity.noContent().build();
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
