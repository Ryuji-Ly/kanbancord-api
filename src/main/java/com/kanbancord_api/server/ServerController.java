package com.kanbancord_api.server;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService serverService;
    private final Authorizer authorizer;
    private final RoleService roleService;
    private final ServerMemberService serverMemberService;

    public ServerController(
            ServerService serverService,
            Authorizer authorizer,
            RoleService roleService,
            ServerMemberService serverMemberService) {
        this.serverService = serverService;
        this.authorizer = authorizer;
        this.roleService = roleService;
        this.serverMemberService = serverMemberService;
    }

    /**
     * Get server by ID.
     */
    @GetMapping("/{serverId}")
    public ResponseEntity<ServerResponse> getServerById(
            @PathVariable Long serverId,
            @CurrentUser Long userId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        return ResponseEntity.ok(mapToServerResponse(server));
    }

    /**
     * Get all roles for a server
     * Only accessible to server members
     */
    @GetMapping("/{serverId}/roles")
    public ResponseEntity<List<RoleResponse>> getServerRoles(
            @PathVariable Long serverId,
            @CurrentUser Long userId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");

        List<Role> roles = roleService.findByServerIdOrderedByPosition(serverId);
        List<RoleResponse> responses = roles.stream()
                .map(this::mapToRoleResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * Get all members of a server
     * Only accessible to server members
     */
    @GetMapping("/{serverId}/members")
    public ResponseEntity<List<ServerMemberResponse>> getServerMembers(
            @PathVariable Long serverId,
            @CurrentUser Long userId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");

        List<ServerMember> members = serverMemberService.findByServerId(serverId);
        List<ServerMemberResponse> responses = members.stream()
                .map(this::mapToServerMemberResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    private ServerResponse mapToServerResponse(Server server) {
        ServerResponse response = new ServerResponse();
        response.setServerId(server.getServerId());
        response.setName(server.getName());
        response.setIconUrl(server.getIconUrl());
        response.setBotPresent(Boolean.TRUE.equals(server.getBotPresent()));
        response.setOwnerId(server.getOwner().getUserId());
        response.setCreatedAt(server.getCreatedAt());
        response.setUpdatedAt(server.getUpdatedAt());
        return response;
    }

    private RoleResponse mapToRoleResponse(Role role) {
        RoleResponse response = new RoleResponse();
        response.setRoleId(role.getRoleId());
        response.setServerId(role.getServer().getServerId());
        response.setName(role.getName());
        response.setColor(role.getColor());
        response.setPosition(role.getPosition());
        response.setDiscordPermissions(role.getDiscordPermissions());
        response.setCreatedAt(role.getCreatedAt());
        response.setUpdatedAt(role.getUpdatedAt());
        return response;
    }

    private ServerMemberResponse mapToServerMemberResponse(ServerMember member) {
        ServerMemberResponse response = new ServerMemberResponse();
        response.setId(member.getId());
        response.setServerId(member.getServer().getServerId());
        response.setUserId(member.getUser().getUserId());
        response.setNickname(member.getNickname());
        String displayName = member.getUser().getGlobalName() != null
                ? member.getUser().getGlobalName()
                : member.getUser().getUsername();
        response.setDisplayName(displayName);
        response.setUsername(member.getUser().getUsername());
        response.setAvatarUrl(member.getUser().getAvatarUrl());
        response.setJoinedAt(member.getJoinedAt());
        return response;
    }
}
