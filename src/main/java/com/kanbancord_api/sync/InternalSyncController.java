package com.kanbancord_api.sync;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.server.MemberRoleService;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.RoleService;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerMember;
import com.kanbancord_api.server.ServerMemberService;
import com.kanbancord_api.server.ServerService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    private final Authorizer authorizer;
    private final ServerService serverService;
    private final UserService userService;
    private final RoleService roleService;
    private final ServerMemberService serverMemberService;
    private final MemberRoleService memberRoleService;
    private final ServerBootstrapService serverBootstrapService;
    private final ApplicationEventPublisher events;

    public InternalSyncController(
            Authorizer authorizer,
            ServerService serverService,
            UserService userService,
            RoleService roleService,
            ServerMemberService serverMemberService,
            MemberRoleService memberRoleService,
            ServerBootstrapService serverBootstrapService,
            ApplicationEventPublisher events) {
        this.authorizer = authorizer;
        this.serverService = serverService;
        this.userService = userService;
        this.roleService = roleService;
        this.serverMemberService = serverMemberService;
        this.memberRoleService = memberRoleService;
        this.serverBootstrapService = serverBootstrapService;
        this.events = events;
    }

    @GetMapping("/servers")
    public ResponseEntity<List<String>> listKnownServerIds(
            @RequestHeader(BOT_TOKEN_HEADER) String botToken) {

        authorizer.requireInternalSyncAccess(botToken);

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

        authorizer.requireInternalSyncAccess(botToken);

        User owner = userService.findById(request.ownerId()).orElseGet(User::new);
        owner.setUserId(request.ownerId());
        owner.setUsername(request.ownerUsername());
        owner.setGlobalName(request.ownerGlobalName());
        owner.setAvatarUrl(request.ownerAvatarUrl());
        owner = userService.update(owner);

        Server server = serverService.findById(serverId).orElseGet(Server::new);
        server.setServerId(serverId);
        server.setName(request.name());
        server.setIconUrl(request.iconUrl());
        server.setBotPresent(true);
        server.setOwner(owner);
        serverService.update(server);

        announce(EventType.SERVER_SYNCED, serverId, serverId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/servers/{serverId}/presence")
    public ResponseEntity<Void> markServerNotPresent(
            @PathVariable Long serverId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken) {

        authorizer.requireInternalSyncAccess(botToken);
        serverService.setBotPresent(serverId, false);
        announce(EventType.SERVER_SYNCED, serverId, serverId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/servers/{serverId}/roles/{roleId}")
    public ResponseEntity<Void> upsertRole(
            @PathVariable Long serverId,
            @PathVariable Long roleId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalRoleSyncRequest request) {

        authorizer.requireInternalSyncAccess(botToken);

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        Role role = roleService.findById(roleId).orElseGet(Role::new);
        if (role.getServer() != null && !role.getServer().getServerId().equals(serverId)) {
            throw new BadRequestException("Role does not belong to the provided server");
        }

        role.setRoleId(roleId);
        role.setServer(server);
        role.setName(request.name());
        role.setColor(request.color());
        role.setPosition(request.position());
        role.setDiscordPermissions(request.discordPermissions());
        roleService.update(role);

        announce(EventType.ROLE_SYNCED, serverId, roleId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/servers/{serverId}/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(
            @PathVariable Long serverId,
            @PathVariable Long roleId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken) {

        authorizer.requireInternalSyncAccess(botToken);
        roleService.findById(roleId).ifPresent(role -> roleService.deleteById(roleId));
        announce(EventType.ROLE_REMOVED, serverId, roleId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/servers/{serverId}/members/{userId}")
    public ResponseEntity<Void> upsertServerMember(
            @PathVariable Long serverId,
            @PathVariable Long userId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalMemberSyncRequest request) {

        authorizer.requireInternalSyncAccess(botToken);

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        User user = userService.findById(userId).orElseGet(User::new);
        user.setUserId(userId);
        user.setUsername(request.username());
        user.setGlobalName(request.globalName());
        user.setAvatarUrl(request.avatarUrl());
        user = userService.update(user);

        ServerMember member = serverMemberService.findByServerIdAndUserId(serverId, userId)
                .orElseGet(ServerMember::new);
        member.setServer(server);
        member.setUser(user);
        member.setNickname(request.nickname());
        if (request.joinedAt() != null) {
            member.setJoinedAt(request.joinedAt());
        }
        member = serverMemberService.update(member);

        if (request.roleIds() != null) {
            memberRoleService.replaceForMember(member, request.roleIds());
        }

        announce(EventType.MEMBER_SYNCED, serverId, userId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/servers/{serverId}/members/{userId}")
    public ResponseEntity<Void> deleteServerMember(
            @PathVariable Long serverId,
            @PathVariable Long userId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken) {

        authorizer.requireInternalSyncAccess(botToken);
        serverMemberService.findByServerIdAndUserId(serverId, userId)
                .ifPresent(member -> serverMemberService.deleteById(member.getId()));
        announce(EventType.MEMBER_REMOVED, serverId, userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/servers/{serverId}/members/{userId}/roles")
    public ResponseEntity<Void> syncMemberRoles(
            @PathVariable Long serverId,
            @PathVariable Long userId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalMemberRoleSyncRequest request) {

        authorizer.requireInternalSyncAccess(botToken);

        ServerMember member = serverMemberService.findByServerIdAndUserId(serverId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ServerMember", "userId", userId));

        memberRoleService.replaceForMember(member, request.roleIds());
        announce(EventType.MEMBER_SYNCED, serverId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/servers/{serverId}/bootstrap")
    public ResponseEntity<Void> bootstrapServerSync(
            @PathVariable Long serverId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody InternalBootstrapRequest request) {

        authorizer.requireInternalSyncAccess(botToken);
        serverBootstrapService.bootstrap(serverId, request);

        announce(EventType.SERVER_SYNCED, serverId, serverId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Tells open pages what changed. Runs once the change is saved; access is re-evaluated and
     * subscriptions that lost it are ended by the realtime publisher.
     */
    private void announce(EventType type, Long serverId, Long entityId) {
        events.publishEvent(new DomainEvent(type, serverId, null, entityId, null, null, null));
    }
}
