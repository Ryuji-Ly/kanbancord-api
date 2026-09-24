package com.kanbancord_api.user;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerResponse;
import com.kanbancord_api.server.ServerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final Authorizer authorizer;
    private final UserService userService;
    private final ServerService serverService;

    public MeController(Authorizer authorizer, UserService userService, ServerService serverService) {
        this.authorizer = authorizer;
        this.userService = userService;
        this.serverService = serverService;
    }

    @GetMapping
    public ResponseEntity<UserResponse> getMe() {
        Long userId = authorizer.requireAuthenticatedUserId();
        User user = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

        return ResponseEntity.ok(toUserResponse(user));
    }

    @GetMapping("/servers")
    public ResponseEntity<List<ServerResponse>> getMyServers() {
        Long userId = authorizer.requireAuthenticatedUserId();

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
        response.setBotPresent(Boolean.TRUE.equals(server.getBotPresent()));
        if (server.getOwner() != null) {
            response.setOwnerId(server.getOwner().getUserId());
        }
        response.setCreatedAt(server.getCreatedAt());
        response.setUpdatedAt(server.getUpdatedAt());
        return response;
    }
}
