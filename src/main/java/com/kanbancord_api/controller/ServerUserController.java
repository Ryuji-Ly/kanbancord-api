package com.kanbancord_api.controller;

import com.kanbancord_api.dto.UserResponse;
import com.kanbancord_api.dto.UserUpdateRequest;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/servers/{serverId}/users")
@Validated
public class ServerUserController {

    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public ServerUserController(
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping("/{targetUserId}")
    public ResponseEntity<UserResponse> getUserByIdInServer(
            @PathVariable Long serverId,
            @PathVariable Long targetUserId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", targetUserId, serverId);

        User user = userService.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", targetUserId));

        return ResponseEntity.ok(mapToResponse(user));
    }

    @PutMapping("/{targetUserId}")
    public ResponseEntity<UserResponse> updateUserInServer(
            @PathVariable Long serverId,
            @PathVariable Long targetUserId,
            @RequestParam Long userId,
            @Valid @RequestBody UserUpdateRequest request) {

        accessValidator.requireUserInServer(userId, serverId);
        accessValidator.requireSelf(userId, targetUserId);
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", targetUserId, serverId);

        User user = userService.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", targetUserId));

        if (request.getGlobalName() != null) {
            user.setGlobalName(request.getGlobalName());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getPreferences() != null) {
            user.setPreferences(request.getPreferences());
        }

        User updated = userService.update(user);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    private UserResponse mapToResponse(User user) {
        UserResponse response = new UserResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setGlobalName(user.getGlobalName());
        response.setAvatarUrl(user.getAvatarUrl());
        response.setPreferences(user.getPreferences());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}
