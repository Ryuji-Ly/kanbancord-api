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
    private final MemberDirectory memberDirectory;

    public ServerController(
            ServerService serverService,
            Authorizer authorizer,
            RoleService roleService,
            ServerMemberService serverMemberService,
            MemberDirectory memberDirectory) {
        this.serverService = serverService;
        this.authorizer = authorizer;
        this.roleService = roleService;
        this.serverMemberService = serverMemberService;
        this.memberDirectory = memberDirectory;
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
     * The server's stored members: everyone when all are stored, otherwise the people who use KanbanCord there or
     * were picked for something. {@link #searchServerMembers} also finds the others.
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

    /**
     * Members whose name contains {@code q}: stored ones, then (when members are stored only as needed) the ones
     * Discord finds by the start of their name. Someone found in Discord has no {@code id} until they are picked.
     */
    @GetMapping("/{serverId}/members/search")
    public ResponseEntity<List<ServerMemberResponse>> searchServerMembers(
            @PathVariable Long serverId,
            @RequestParam(name = "q", defaultValue = "") String query,
            @CurrentUser Long userId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");
        String trimmed = query.length() > 100 ? query.substring(0, 100) : query;
        return ResponseEntity.ok(memberDirectory.search(serverId, trimmed));
    }

    private ServerResponse mapToServerResponse(Server server) {
        return new ServerResponse(
                server.getServerId(),
                server.getName(),
                server.getIconUrl(),
                Boolean.TRUE.equals(server.getBotPresent()),
                server.getOwner().getUserId(),
                server.getCreatedAt(),
                server.getUpdatedAt());
    }

    private RoleResponse mapToRoleResponse(Role role) {
        return new RoleResponse(
                role.getRoleId(),
                role.getServer().getServerId(),
                role.getName(),
                role.getColor(),
                role.getPosition(),
                role.getDiscordPermissions(),
                role.getCreatedAt(),
                role.getUpdatedAt());
    }

    private ServerMemberResponse mapToServerMemberResponse(ServerMember member) {
        String displayName = member.getUser().getGlobalName() != null
                ? member.getUser().getGlobalName()
                : member.getUser().getUsername();
        return new ServerMemberResponse(
                member.getId(),
                member.getServer().getServerId(),
                member.getUser().getUserId(),
                member.getNickname(),
                displayName,
                member.getUser().getUsername(),
                member.getUser().getAvatarUrl(),
                member.getJoinedAt());
    }
}
