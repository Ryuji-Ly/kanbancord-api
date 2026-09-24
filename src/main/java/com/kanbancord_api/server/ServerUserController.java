package com.kanbancord_api.server;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.UserEvent;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.security.CurrentUser;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserResponse;
import com.kanbancord_api.user.UserService;
import com.kanbancord_api.user.UserUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/servers/{serverId}/users")
@Validated
public class ServerUserController {

    private final UserService userService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public ServerUserController(
            UserService userService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events) {
        this.userService = userService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    @GetMapping("/{targetUserId}")
    public ResponseEntity<UserResponse> getUserByIdInServer(
            @PathVariable Long serverId,
            @PathVariable Long targetUserId,
            @CurrentUser Long userId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", targetUserId, serverId);

        User user = userService.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", targetUserId));

        return ResponseEntity.ok(mapToResponse(user, targetUserId.equals(userId)));
    }

    @PutMapping("/{targetUserId}")
    public ResponseEntity<UserResponse> updateUserInServer(
            @PathVariable Long serverId,
            @PathVariable Long targetUserId,
            @CurrentUser Long userId,
            @Valid @RequestBody UserUpdateRequest request) {

        authorizer.requireUserInServer(userId, serverId);
        authorizer.requireSelf(userId, targetUserId);
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
        UserResponse response = mapToResponse(updated, true);
        // The same user's other tabs and devices pick up the new profile and preferences.
        events.publishEvent(new UserEvent(UserEvent.Type.PROFILE_UPDATED, updated.getUserId(), response));
        return ResponseEntity.ok(response);
    }

    /** Preferences are private to their owner and only included when a user reads themselves. */
    private UserResponse mapToResponse(User user, boolean includePreferences) {
        UserResponse response = new UserResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setGlobalName(user.getGlobalName());
        response.setAvatarUrl(user.getAvatarUrl());
        if (includePreferences) {
            response.setPreferences(user.getPreferences());
        }
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}
