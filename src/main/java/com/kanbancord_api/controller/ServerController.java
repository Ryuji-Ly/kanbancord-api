package com.kanbancord_api.controller;

import com.kanbancord_api.dto.RoleResponse;
import com.kanbancord_api.dto.ServerMemberResponse;
import com.kanbancord_api.dto.ServerResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.service.RoleService;
import com.kanbancord_api.service.ServerAccessValidator;
import com.kanbancord_api.service.ServerMemberService;
import com.kanbancord_api.service.ServerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService serverService;
    private final ServerAccessValidator accessValidator;
    private final RoleService roleService;
    private final ServerMemberService serverMemberService;

    public ServerController(
            ServerService serverService,
            ServerAccessValidator accessValidator,
            RoleService roleService,
            ServerMemberService serverMemberService) {
        this.serverService = serverService;
        this.accessValidator = accessValidator;
        this.roleService = roleService;
        this.serverMemberService = serverMemberService;
    }

    /**
     * Get server by ID
     * TODO: Extract userId from authenticated session
     */
    @GetMapping("/{serverId}")
    public ResponseEntity<ServerResponse> getServerById(
            @PathVariable Long serverId,
            @RequestParam(required = false) Long userId) { // TODO: Remove this param once auth is implemented

        // TODO: Get userId from security context instead of request param
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

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
            @RequestParam(required = false) Long userId) { // TODO: Remove this param once auth is implemented

        // TODO: Get userId from security context
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

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
            @RequestParam(required = false) Long userId) { // TODO: Remove this param once auth is implemented

        // TODO: Get userId from security context
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

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
        response.setJoinedAt(member.getJoinedAt());
        return response;
    }
}
