package com.kanbancord_api.unit.service;

import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.model.User;
import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.DiscordPermissionParser;
import com.kanbancord_api.repository.PermissionRepository;
import com.kanbancord_api.service.KanbanPermissionService;
import com.kanbancord_api.service.MemberRoleService;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.RoleService;
import com.kanbancord_api.service.ServerMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionEvaluationServiceTest {

    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private KanbanPermissionService kanbanPermissionService;
    @Mock
    private ServerMemberService serverMemberService;
    @Mock
    private MemberRoleService memberRoleService;
    @Mock
    private RoleService roleService;

    private PermissionEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new PermissionEvaluationService(
                permissionRepository,
                kanbanPermissionService,
                serverMemberService,
                memberRoleService,
                roleService,
                new DiscordPermissionParser());
    }

    @Test
    void resolve_prefersUserRule_overRoleAndDiscord() {
        KanbanPermission key = new KanbanPermission();
        key.setPermissionId(1);
        when(kanbanPermissionService.findByKey("EDIT_TASK")).thenReturn(Optional.of(key));

        ServerMember member = member(7L, 10L, 99L);
        when(serverMemberService.findByServerIdAndUserId(10L, 99L)).thenReturn(Optional.of(member));

        MemberRole memberRole = new MemberRole();
        Role role = new Role();
        role.setRoleId(88L);
        memberRole.setRole(role);
        when(memberRoleService.findByServerMemberId(7L)).thenReturn(List.of(memberRole));

        Role fullRole = new Role();
        fullRole.setRoleId(88L);
        fullRole.setDiscordPermissions(DiscordPermissionFlag.ADMINISTRATOR.getBit());
        when(roleService.findById(88L)).thenReturn(Optional.of(fullRole));

        when(permissionRepository.findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdOrderByPriorityDescIdDesc(
                eq("SERVER"), eq(10L), eq("USER"), eq(99L)))
                .thenReturn(List.of(permission(4L, 1, "DENY", 100, "SERVER", 10L)));

        PermissionEvaluationService.Decision decision = service.resolve(10L, null, 99L, "EDIT_TASK");

        assertFalse(decision.allowed());
        assertEquals("USER", decision.sourceTier());
        assertEquals(4L, decision.sourcePermissionId());
    }

    @Test
    void resolve_prefersBoardScopedRule_overServerScopedRule_withinSameTier() {
        KanbanPermission key = new KanbanPermission();
        key.setPermissionId(1);
        when(kanbanPermissionService.findByKey("VIEW_BOARD")).thenReturn(Optional.of(key));

        ServerMember member = member(7L, 10L, 99L);
        when(serverMemberService.findByServerIdAndUserId(10L, 99L)).thenReturn(Optional.of(member));

        when(memberRoleService.findByServerMemberId(7L)).thenReturn(List.of());

        when(permissionRepository.findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdOrderByPriorityDescIdDesc(
                eq("BOARD"), eq(55L), eq("USER"), eq(99L)))
                .thenReturn(List.of(permission(10L, 1, "ALLOW", 10, "BOARD", 55L)));

        when(permissionRepository.findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdOrderByPriorityDescIdDesc(
                eq("SERVER"), eq(10L), eq("USER"), eq(99L)))
                .thenReturn(List.of(permission(11L, 1, "DENY", 999, "SERVER", 10L)));

        PermissionEvaluationService.Decision decision = service.resolve(10L, 55L, 99L, "VIEW_BOARD");

        assertTrue(decision.allowed());
        assertEquals("BOARD", decision.sourceScopeType());
        assertEquals(10L, decision.sourcePermissionId());
    }

    private static Permission permission(Long id, Integer kanbanPermissionId, String state, Integer priority,
            String scopeType, Long scopeId) {
        KanbanPermission kanbanPermission = new KanbanPermission();
        kanbanPermission.setPermissionId(kanbanPermissionId);

        Permission permission = new Permission();
        permission.setId(id);
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(state);
        permission.setPriority(priority);
        permission.setScopeType(scopeType);
        permission.setScopeId(scopeId);
        return permission;
    }

    private static ServerMember member(Long memberId, Long serverId, Long userId) {
        Server server = new Server();
        server.setServerId(serverId);
        User user = new User();
        user.setUserId(userId);
        user.setUsername("user-" + userId);

        ServerMember member = new ServerMember();
        member.setId(memberId);
        member.setServer(server);
        member.setUser(user);
        return member;
    }
}
