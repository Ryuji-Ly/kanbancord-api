package com.kanbancord_api.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.controller.InternalSyncController;
import com.kanbancord_api.dto.InternalBootstrapRequest;
import com.kanbancord_api.dto.InternalMemberSyncRequest;
import com.kanbancord_api.dto.InternalRoleSyncRequest;
import com.kanbancord_api.dto.InternalServerSyncRequest;
import com.kanbancord_api.exception.AccessDeniedException;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalSyncController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(com.kanbancord_api.exception.GlobalExceptionHandler.class)
class InternalSyncControllerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccessValidator accessValidator;
    @MockitoBean
    private ServerService serverService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private ServerMemberService serverMemberService;
    @MockitoBean
    private PermissionBootstrapService permissionBootstrapService;

    @Test
    void upsertServer_returnsNoContent_onHappyPath() throws Exception {
        InternalServerSyncRequest request = new InternalServerSyncRequest();
        request.setName("Test Server");
        request.setIconUrl("https://cdn.example/icon.png");
        request.setOwnerId(100L);
        request.setOwnerUsername("owner");
        request.setOwnerGlobalName("Owner Name");
        request.setOwnerAvatarUrl("https://cdn.example/avatar.png");

        User owner = new User();
        owner.setUserId(100L);

        when(userService.findById(100L)).thenReturn(Optional.empty());
        when(userService.update(any(User.class))).thenReturn(owner);
        when(serverService.findById(1L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/internal/sync/servers/1")
                .header("X-Internal-Bot-Token", "valid-bot-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        verify(accessValidator).requireInternalSyncAccess("valid-bot-token");
        verify(serverService).update(any(Server.class));
    }

    @Test
    void upsertServer_returnsForbidden_whenTokenInvalid_unhappyPath() throws Exception {
        InternalServerSyncRequest request = new InternalServerSyncRequest();
        request.setName("Test Server");
        request.setOwnerId(100L);
        request.setOwnerUsername("owner");

        doThrow(new AccessDeniedException("Invalid internal sync bot token"))
                .when(accessValidator)
                .requireInternalSyncAccess("bad-token");

        mockMvc.perform(put("/api/internal/sync/servers/1")
                .header("X-Internal-Bot-Token", "bad-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void upsertRole_returnsNotFound_whenServerMissing_unhappyPath() throws Exception {
        InternalRoleSyncRequest request = new InternalRoleSyncRequest();
        request.setName("Admin");
        request.setDiscordPermissions(8L);

        when(serverService.findById(1L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/internal/sync/servers/1/roles/10")
                .header("X-Internal-Bot-Token", "valid-bot-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void upsertRole_returnsBadRequest_whenRoleBelongsToDifferentServer_edgeCase() throws Exception {
        InternalRoleSyncRequest request = new InternalRoleSyncRequest();
        request.setName("Admin");
        request.setDiscordPermissions(8L);

        Server server1 = new Server();
        server1.setServerId(1L);

        Server otherServer = new Server();
        otherServer.setServerId(2L);

        Role existingRole = new Role();
        existingRole.setRoleId(10L);
        existingRole.setServer(otherServer);

        when(serverService.findById(1L)).thenReturn(Optional.of(server1));
        when(roleService.findById(10L)).thenReturn(Optional.of(existingRole));

        mockMvc.perform(put("/api/internal/sync/servers/1/roles/10")
                .header("X-Internal-Bot-Token", "valid-bot-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertServerMember_returnsNoContent_onHappyPath() throws Exception {
        InternalMemberSyncRequest request = new InternalMemberSyncRequest();
        request.setUsername("user1");
        request.setGlobalName("User One");
        request.setAvatarUrl("https://cdn.example/a.png");
        request.setNickname("Nick");
        request.setJoinedAt(LocalDateTime.now());

        Server server = new Server();
        server.setServerId(1L);

        User user = new User();
        user.setUserId(5L);

        when(serverService.findById(1L)).thenReturn(Optional.of(server));
        when(userService.findById(5L)).thenReturn(Optional.empty());
        when(userService.update(any(User.class))).thenReturn(user);
        when(serverMemberService.findByServerIdAndUserId(1L, 5L)).thenReturn(Optional.of(new ServerMember()));

        mockMvc.perform(put("/api/internal/sync/servers/1/members/5")
                .header("X-Internal-Bot-Token", "valid-bot-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        verify(serverMemberService).update(any(ServerMember.class));
    }

    @Test
    void bootstrapServerSync_returnsNoContent_onHappyPath() throws Exception {
        InternalBootstrapRequest request = new InternalBootstrapRequest();
        request.setName("Bootstrapped Server");
        request.setOwnerId(200L);
        request.setOwnerUsername("bootstrap-owner");

        InternalBootstrapRequest.RoleEntry role = new InternalBootstrapRequest.RoleEntry();
        role.setRoleId(10L);
        role.setName("Moderator");
        role.setDiscordPermissions(8L);
        request.setRoles(java.util.List.of(role));

        InternalBootstrapRequest.MemberEntry member = new InternalBootstrapRequest.MemberEntry();
        member.setUserId(300L);
        member.setUsername("member1");
        request.setMembers(java.util.List.of(member));

        User owner = new User();
        owner.setUserId(200L);
        Server server = new Server();
        server.setServerId(123L);
        User memberUser = new User();
        memberUser.setUserId(300L);

        when(userService.findById(200L)).thenReturn(Optional.of(owner));
        when(userService.update(any(User.class))).thenReturn(owner).thenReturn(memberUser);
        when(serverService.findById(eq(123L))).thenReturn(Optional.of(server));
        when(serverService.update(any(Server.class))).thenReturn(server);
        when(roleService.findById(10L)).thenReturn(Optional.empty());
        when(serverMemberService.findByServerIdAndUserId(eq(123L), eq(300L))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/internal/sync/servers/123/bootstrap")
                .header("X-Internal-Bot-Token", "valid-bot-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        verify(roleService).update(any(Role.class));
        verify(serverMemberService).update(any(ServerMember.class));
        verify(permissionBootstrapService).initializeDefaultServerConfiguration(123L);
    }

    @Test
    void bootstrapServerSync_returnsBadRequest_whenRoleEntryMissingDiscordPermissions_unhappyPath() throws Exception {
        String json = "{\"name\":\"Server\",\"ownerId\":1,\"ownerUsername\":\"owner\","
                + "\"roles\":[{\"roleId\":10,\"name\":\"Mod\"}],\"members\":[]}";

        mockMvc.perform(post("/api/internal/sync/servers/123/bootstrap")
                .header("X-Internal-Bot-Token", "valid-bot-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest());
    }
}
