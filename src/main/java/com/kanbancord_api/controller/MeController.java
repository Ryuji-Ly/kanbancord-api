package com.kanbancord_api.controller;

import com.kanbancord_api.dto.ServerResponse;
import com.kanbancord_api.dto.UserResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ServerService;
import com.kanbancord_api.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AccessValidator accessValidator;
    private final UserService userService;
    private final ServerService serverService;

    public MeController(AccessValidator accessValidator, UserService userService, ServerService serverService) {
        this.accessValidator = accessValidator;
        this.userService = userService;
        this.serverService = serverService;
    }

    @GetMapping
    public ResponseEntity<UserResponse> getMe() {
        Long userId = accessValidator.requireAuthenticatedUserId();
        User user = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        return ResponseEntity.ok(toUserResponse(user));
    }

    @GetMapping("/servers")
    public ResponseEntity<List<ServerResponse>> getMyServers() {
        Long userId = accessValidator.requireAuthenticatedUserId();

        List<ServerResponse> responses = serverService.findByMemberUserId(userId)
                .stream()
                .map(this::toServerResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    private UserResponse toUserResponse(User user) {
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

    private ServerResponse toServerResponse(Server server) {
        ServerResponse response = new ServerResponse();
        response.setServerId(server.getServerId());
        response.setName(server.getName());
        response.setIconUrl(server.getIconUrl());
        if (server.getOwner() != null) {
            response.setOwnerId(server.getOwner().getUserId());
        }
        response.setCreatedAt(server.getCreatedAt());
        response.setUpdatedAt(server.getUpdatedAt());
        return response;
    }
}
