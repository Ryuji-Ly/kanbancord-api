package com.kanbancord_api.controller;

import com.kanbancord_api.dto.InternalBootstrapRequest;
import com.kanbancord_api.dto.InternalMemberSyncRequest;
import com.kanbancord_api.dto.InternalRoleSyncRequest;
import com.kanbancord_api.dto.InternalServerSyncRequest;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.PermissionBootstrapService;
import com.kanbancord_api.service.RoleService;
import com.kanbancord_api.service.ServerMemberService;
import com.kanbancord_api.service.ServerService;
import com.kanbancord_api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/internal/sync")
@Validated
public class InternalSyncController {

    private static final String BOT_TOKEN_HEADER = "X-Internal-Bot-Token";

    private final AccessValidator accessValidator;
    private final ServerService serverService;
    private final UserService userService;
    private final RoleService roleService;
    private final ServerMemberService serverMemberService;
    private final PermissionBootstrapService permissionBootstrapService;

    public InternalSyncController(
            AccessValidator accessValidator,
            ServerService serverService,
            UserService userService,
            RoleService roleService,
            ServerMemberService serverMemberService,
            PermissionBootstrapService permissionBootstrapService) {
        this.accessValidator = accessValidator;
        this.serverService = serverService;
        this.userService = userService;
        this.roleService = roleService;
        this.serverMemberService = serverMemberService;
        this.permissionBootstrapService = permissionBootstrapService;
    }

    @GetMapping("/servers")
    public ResponseEntity<List<String>> listKnownServerIds(
            @RequestHeader(BOT_TOKEN_HEADER) String botToken) {

        accessValidator.requireInternalSyncAccess(botToken);

        List<String> serverIds = serverService.findAll()
                .stream()
                .map(server -> server.getServerId().toString())
                .toList();

        return ResponseEntity.ok(serverIds);
    }

    @PutMapping("/servers/{serverId}")
    public ResponseEntity<Void> upsertServer(
            @PathVariable Long serverId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalServerSyncRequest request) {

        accessValidator.requireInternalSyncAccess(botToken);

        User owner = userService.findById(request.getOwnerId()).orElseGet(User::new);
        owner.setUserId(request.getOwnerId());
        owner.setUsername(request.getOwnerUsername());
        owner.setGlobalName(request.getOwnerGlobalName());
        owner.setAvatarUrl(request.getOwnerAvatarUrl());
        owner = userService.update(owner);

        Server server = serverService.findById(serverId).orElseGet(Server::new);
        server.setServerId(serverId);
        server.setName(request.getName());
        server.setIconUrl(request.getIconUrl());
        server.setOwner(owner);
        serverService.update(server);

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/servers/{serverId}/roles/{roleId}")
    public ResponseEntity<Void> upsertRole(
            @PathVariable Long serverId,
            @PathVariable Long roleId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalRoleSyncRequest request) {

        accessValidator.requireInternalSyncAccess(botToken);

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        Role role = roleService.findById(roleId).orElseGet(Role::new);
        if (role.getServer() != null && !role.getServer().getServerId().equals(serverId)) {
            throw new BadRequestException("Role does not belong to the provided server");
        }

        role.setRoleId(roleId);
        role.setServer(server);
        role.setName(request.getName());
        role.setColor(request.getColor());
        role.setPosition(request.getPosition());
        role.setDiscordPermissions(request.getDiscordPermissions());
        roleService.update(role);

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/servers/{serverId}/members/{userId}")
    public ResponseEntity<Void> upsertServerMember(
            @PathVariable Long serverId,
            @PathVariable Long userId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalMemberSyncRequest request) {

        accessValidator.requireInternalSyncAccess(botToken);

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        User user = userService.findById(userId).orElseGet(User::new);
        user.setUserId(userId);
        user.setUsername(request.getUsername());
        user.setGlobalName(request.getGlobalName());
        user.setAvatarUrl(request.getAvatarUrl());
        user = userService.update(user);

        ServerMember member = serverMemberService.findByServerIdAndUserId(serverId, userId)
                .orElseGet(ServerMember::new);
        member.setServer(server);
        member.setUser(user);
        member.setNickname(request.getNickname());
        if (request.getJoinedAt() != null) {
            member.setJoinedAt(request.getJoinedAt());
        }
        serverMemberService.update(member);

        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/servers/{serverId}/bootstrap")
    public ResponseEntity<Void> bootstrapServerSync(
            @PathVariable Long serverId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalBootstrapRequest request) {

        accessValidator.requireInternalSyncAccess(botToken);

        User owner = userService.findById(request.getOwnerId()).orElseGet(User::new);
        owner.setUserId(request.getOwnerId());
        owner.setUsername(request.getOwnerUsername());
        owner.setGlobalName(request.getOwnerGlobalName());
        owner.setAvatarUrl(request.getOwnerAvatarUrl());
        owner = userService.update(owner);

        Server server = serverService.findById(serverId).orElseGet(Server::new);
        server.setServerId(serverId);
        server.setName(request.getName());
        server.setIconUrl(request.getIconUrl());
        server.setOwner(owner);
        server = serverService.update(server);

        for (InternalBootstrapRequest.RoleEntry entry : request.getRoles()) {
            Role role = roleService.findById(entry.getRoleId()).orElseGet(Role::new);
            role.setRoleId(entry.getRoleId());
            role.setServer(server);
            role.setName(entry.getName());
            role.setColor(entry.getColor());
            role.setPosition(entry.getPosition());
            role.setDiscordPermissions(entry.getDiscordPermissions());
            roleService.update(role);
        }

        for (InternalBootstrapRequest.MemberEntry entry : request.getMembers()) {
            User user = userService.findById(entry.getUserId()).orElseGet(User::new);
            user.setUserId(entry.getUserId());
            user.setUsername(entry.getUsername());
            user.setGlobalName(entry.getGlobalName());
            user.setAvatarUrl(entry.getAvatarUrl());
            user = userService.update(user);

            ServerMember member = serverMemberService.findByServerIdAndUserId(serverId, entry.getUserId())
                    .orElseGet(ServerMember::new);
            member.setServer(server);
            member.setUser(user);
            member.setNickname(entry.getNickname());
            if (entry.getJoinedAt() != null) {
                member.setJoinedAt(entry.getJoinedAt());
            }
            serverMemberService.update(member);
        }

        permissionBootstrapService.initializeDefaultServerConfiguration(serverId);

        return ResponseEntity.noContent().build();
    }
}
