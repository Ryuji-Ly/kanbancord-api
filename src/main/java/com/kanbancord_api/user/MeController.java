package com.kanbancord_api.user;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.auth.DiscordGuildResponse;
import com.kanbancord_api.auth.DiscordGuildService;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.server.MemberDirectory;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerResponse;
import com.kanbancord_api.server.ServerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final Authorizer authorizer;
    private final UserService userService;
    private final ServerService serverService;
    private final MemberDirectory memberDirectory;
    private final DiscordGuildService discordGuildService;

    public MeController(Authorizer authorizer, UserService userService, ServerService serverService,
                        MemberDirectory memberDirectory, DiscordGuildService discordGuildService) {
        this.authorizer = authorizer;
        this.userService = userService;
        this.serverService = serverService;
        this.memberDirectory = memberDirectory;
        this.discordGuildService = discordGuildService;
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

        Map<Long, Server> servers = new LinkedHashMap<>();
        serverService.findByMemberUserId(userId).forEach(server -> servers.put(server.getServerId(), server));
        if (memberDirectory.lazy()) {
            // People are only stored as members of servers they have used KanbanCord in, so the servers they are
            // in on Discord (from their sign-in) that have the bot count too; opening one stores them.
            List<Long> guildIds;
            try {
                guildIds = discordGuildService.guilds(userId).stream().map(DiscordGuildResponse::id)
                        .map(Long::valueOf).toList();
            } catch (RuntimeException ex) {
                // Signed in through the bot, or Discord unavailable: the stored servers are what we know.
                guildIds = List.of();
            }
            serverService.findAllById(guildIds).stream()
                    .filter(server -> Boolean.TRUE.equals(server.getBotPresent()))
                    .forEach(server -> servers.putIfAbsent(server.getServerId(), server));
        }

        List<ServerResponse> responses = servers.values().stream()
                .map(this::toServerResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getUsername(),
                user.getGlobalName(),
                user.getAvatarUrl(),
                user.getPreferences(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    private ServerResponse toServerResponse(Server server) {
        return new ServerResponse(
                server.getServerId(),
                server.getName(),
                server.getIconUrl(),
                Boolean.TRUE.equals(server.getBotPresent()),
                server.getOwner() == null ? null : server.getOwner().getUserId(),
                server.getCreatedAt(),
                server.getUpdatedAt());
    }
}
