package com.kanbancord_api.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.GlobalExceptionHandler;
import com.kanbancord_api.notification.Notification;
import com.kanbancord_api.notification.NotificationController;
import com.kanbancord_api.notification.NotificationService;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.RoleService;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerController;
import com.kanbancord_api.server.ServerMember;
import com.kanbancord_api.server.ServerMemberService;
import com.kanbancord_api.server.ServerService;
import com.kanbancord_api.server.ServerUserController;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import com.kanbancord_api.user.UserUpdateRequest;
import static com.kanbancord_api.api.ApiTestAuth.asUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { ServerController.class, ServerUserController.class, NotificationController.class })
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ServerAccessControllersApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ServerService serverService;
    @MockitoBean
    private Authorizer authorizer;
    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private ServerMemberService serverMemberService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ResourceValidator resourceValidator;
    @MockitoBean
    private NotificationService notificationService;

    @Test
    void getServerById_returnsOk_happyPath() throws Exception {
        Server server = server(1L, "Main", user(10L, "owner"));
        when(serverService.findById(1L)).thenReturn(Optional.of(server));

        mockMvc.perform(get("/api/servers/1").with(asUser(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverId").value(1));
    }

    @Test
    void getServerById_returnsForbidden_unhappyPath() throws Exception {
        doThrow(new AccessDeniedException("Forbidden"))
                .when(authorizer)
                .requireServerPermission(99L, 1L, "VIEW_SERVER");

        mockMvc.perform(get("/api/servers/1").with(asUser(99L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getServerRoles_returnsOk_happyPath() throws Exception {
        Role role = role(5L, 1L);
        when(roleService.findByServerIdOrderedByPosition(1L)).thenReturn(List.of(role));

        mockMvc.perform(get("/api/servers/1/roles").with(asUser(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleId").value(5));
    }

    @Test
    void getServerMembers_returnsOk_happyPath() throws Exception {
        ServerMember member = member(20L, 1L, 10L);
        when(serverMemberService.findByServerId(1L)).thenReturn(List.of(member));

        mockMvc.perform(get("/api/servers/1/members").with(asUser(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(20));
    }

    @Test
    void getUserByIdInServer_returnsOk_happyPath() throws Exception {
        User target = user(30L, "target");
        when(userService.findById(30L)).thenReturn(Optional.of(target));

        mockMvc.perform(get("/api/servers/1/users/30").with(asUser(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(30));
    }

    @Test
    void getUserByIdInServer_returnsNotFound_unhappyPath() throws Exception {
        when(userService.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/servers/1/users/404").with(asUser(10L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateUserInServer_returnsOk_happyPath() throws Exception {
        UserUpdateRequest request = new UserUpdateRequest(
                "Updated Name",
                "https://cdn.example/avatar.png",
                Map.of("theme", "dark"));

        User existing = user(30L, "target");
        when(userService.findById(30L)).thenReturn(Optional.of(existing));
        when(userService.update(any(User.class))).thenReturn(existing);

        mockMvc.perform(put("/api/servers/1/users/30")
                .with(asUser(30L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(30));
    }

    @Test
    void updateUserInServer_returnsForbidden_whenNotSelf_unhappyPath() throws Exception {
        UserUpdateRequest request = new UserUpdateRequest("Updated Name", null, null);

        doThrow(new AccessDeniedException("Only self updates allowed"))
                .when(authorizer)
                .requireSelf(99L, 30L);

        mockMvc.perform(put("/api/servers/1/users/30")
                .with(asUser(99L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getNotificationsByUserId_returnsOk_happyPath() throws Exception {
        Notification notification = notification(1L, 30L);
        when(notificationService.findByUserIdOrdered(30L)).thenReturn(List.of(notification));

        mockMvc.perform(get("/api/users/30/notifications").with(asUser(30L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].notificationId").value(1));
    }

    @Test
    void getNotificationById_returnsOk_happyPath() throws Exception {
        Notification notification = notification(2L, 30L);
        when(notificationService.findById(2L)).thenReturn(Optional.of(notification));

        mockMvc.perform(get("/api/users/30/notifications/2").with(asUser(30L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(2));
    }

    @Test
    void getNotificationById_returnsNotFound_unhappyPath() throws Exception {
        when(notificationService.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/30/notifications/404").with(asUser(30L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUnreadCount_returnsOk_happyPath() throws Exception {
        when(notificationService.countUnreadByUserId(30L)).thenReturn(3L);

        mockMvc.perform(get("/api/users/30/notifications/unread-count").with(asUser(30L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(3));
    }

    @Test
    void markAsRead_returnsNoContent_happyPath() throws Exception {
        Notification notification = notification(7L, 30L);
        when(notificationService.findById(7L)).thenReturn(Optional.of(notification));

        mockMvc.perform(patch("/api/users/30/notifications/7/mark-read").with(asUser(30L)))
                .andExpect(status().isNoContent());

        verify(notificationService).markAsRead(7L);
    }

    @Test
    void markAllAsReadForUser_returnsNoContent_happyPath() throws Exception {
        mockMvc.perform(patch("/api/users/30/notifications/mark-all-read").with(asUser(30L)))
                .andExpect(status().isNoContent());

        verify(notificationService).markAllAsReadForUser(30L);
    }

    private static User user(Long userId, String username) {
        User user = new User();
        user.setUserId(userId);
        user.setUsername(username);
        return user;
    }

    private static Server server(Long serverId, String name, User owner) {
        Server server = new Server();
        server.setServerId(serverId);
        server.setName(name);
        server.setOwner(owner);
        return server;
    }

    private static Role role(Long roleId, Long serverId) {
        Role role = new Role();
        role.setRoleId(roleId);
        role.setServer(server(serverId, "Main", user(10L, "owner")));
        role.setName("Role");
        return role;
    }

    private static ServerMember member(Long memberId, Long serverId, Long userId) {
        ServerMember member = new ServerMember();
        member.setId(memberId);
        member.setServer(server(serverId, "Main", user(10L, "owner")));
        member.setUser(user(userId, "member"));
        return member;
    }

    private static Notification notification(Long id, Long userId) {
        Notification notification = new Notification();
        notification.setNotificationId(id);
        notification.setUser(user(userId, "target"));
        notification.setType("TASK");
        notification.setMessage("Changed");
        notification.setIsRead(false);
        return notification;
    }
}
