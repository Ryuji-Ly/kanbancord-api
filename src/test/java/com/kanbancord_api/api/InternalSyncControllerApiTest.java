package com.kanbancord_api.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.permission.PermissionBootstrapService;
import com.kanbancord_api.realtime.RealtimeSubscriptionRevoker;
import com.kanbancord_api.server.MemberRoleService;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.RoleService;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerMember;
import com.kanbancord_api.server.ServerMemberService;
import com.kanbancord_api.server.ServerService;
import com.kanbancord_api.sync.InternalBootstrapRequest;
import com.kanbancord_api.sync.InternalMemberSyncRequest;
import com.kanbancord_api.sync.InternalRoleSyncRequest;
import com.kanbancord_api.sync.InternalServerSyncRequest;
import com.kanbancord_api.sync.InternalSyncController;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
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
    private Authorizer authorizer;
    @MockitoBean
    private ServerService serverService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private ServerMemberService serverMemberService;
    @MockitoBean
    private com.kanbancord_api.sync.ServerBootstrapService serverBootstrapService;
    @MockitoBean
    private MemberRoleService memberRoleService;
    @MockitoBean
    private RealtimeSubscriptionRevoker realtimeSubscriptionRevoker;

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

        verify(authorizer).requireInternalSyncAccess("valid-bot-token");
        verify(serverService).update(any(Server.class));
    }

    @Test
    void upsertServer_returnsForbidden_whenTokenInvalid_unhappyPath() throws Exception {
        InternalServerSyncRequest request = new InternalServerSyncRequest();
        request.setName("Test Server");
        request.setOwnerId(100L);
        request.setOwnerUsername("owner");

        doThrow(new AccessDeniedException("Invalid internal sync bot token"))
                .when(authorizer)
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

        mockMvc.perform(post("/api/internal/sync/servers/123/bootstrap")
                .header("X-Internal-Bot-Token", "valid-bot-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // What the sync writes is covered end to end, against a real database.
        verify(serverBootstrapService).bootstrap(eq(123L), any(InternalBootstrapRequest.class));
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
