package com.kanbancord_api.controller;

import com.kanbancord_api.dto.UserResponse;
import com.kanbancord_api.dto.UserUpdateRequest;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Get current authenticated user
     * TODO: Implement actual authentication and extract user ID from JWT token
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        // TODO: Replace with actual authenticated user ID from security context
        // For now, returning a mock response to demonstrate the endpoint structure
        throw new UnsupportedOperationException("Authentication not yet implemented. Use GET /users/{id} instead.");
    }

    /**
     * Get a user by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        User user = userService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", id));

        return ResponseEntity.ok(mapToResponse(user));
    }

    /**
     * Update user profile
     * TODO: Add authorization check to ensure user can only update their own
     * profile
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserUpdateRequest request) {

        User user = userService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", id));

        // TODO: Add authorization check here
        // if (!currentUserId.equals(id)) throw new AccessDeniedException();

        // Update only the allowed fields
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
