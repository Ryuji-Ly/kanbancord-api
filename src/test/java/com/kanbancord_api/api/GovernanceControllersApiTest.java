package com.kanbancord_api.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.controller.AuditLogController;
import com.kanbancord_api.controller.KanbanPermissionController;
import com.kanbancord_api.controller.MemberRoleController;
import com.kanbancord_api.controller.PermissionController;
import com.kanbancord_api.dto.AuditLogRequest;
import com.kanbancord_api.dto.KanbanPermissionRequest;
import com.kanbancord_api.dto.MemberRoleRequest;
import com.kanbancord_api.dto.PermissionRequest;
import com.kanbancord_api.exception.GlobalExceptionHandler;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.AuditLog;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.AuditLogService;
import com.kanbancord_api.service.KanbanPermissionService;
import com.kanbancord_api.service.MemberRoleService;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.PermissionService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.ServerService;
import com.kanbancord_api.service.UserService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuditLogController.class,
        KanbanPermissionController.class,
        PermissionController.class,
        MemberRoleController.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class GovernanceControllersApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

        @MockitoBean
    private AuditLogService auditLogService;
        @MockitoBean
    private ServerService serverService;
        @MockitoBean
    private UserService userService;
        @MockitoBean
    private AccessValidator accessValidator;
        @MockitoBean
    private ResourceValidator resourceValidator;
        @MockitoBean
    private KanbanPermissionService kanbanPermissionService;
        @MockitoBean
    private PermissionService permissionService;
        @MockitoBean
    private PermissionEvaluationService permissionEvaluationService;
        @MockitoBean
    private MemberRoleService memberRoleService;

    @Test
    void auditLogEndpoints_coverHappyAndUnhappy() throws Exception {
        AuditLogRequest request = new AuditLogRequest();
        request.setServerId(1L);
        request.setBoardId(100L);
        request.setUserId(10L);
        request.setAction("TASK_CREATED");
        request.setEntityType("TASK");
        request.setEntityId(123L);
        request.setSource("API");
        request.setChanges(Map.of("field", "value"));

        AuditLog log = auditLog(900L);
        when(serverService.findById(1L)).thenReturn(Optional.of(server(1L)));
        when(userService.findById(10L)).thenReturn(Optional.of(user(10L)));
        when(resourceValidator.requireBoardInServer(100L, 1L)).thenReturn(board(100L));
        when(auditLogService.create(any(AuditLog.class))).thenReturn(log);
        when(auditLogService.findByServerIdOrdered(1L)).thenReturn(List.of(log));
        when(resourceValidator.requireAuditLogInServer(900L, 1L)).thenReturn(log);
        when(auditLogService.update(any(AuditLog.class))).thenReturn(log);

        mockMvc.perform(post("/api/servers/1/audit-logs").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.logId").value(900));

        mockMvc.perform(get("/api/servers/1/audit-logs").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/audit-logs/900").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/audit-logs/900").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/audit-logs/900").param("userId", "10"))
                .andExpect(status().isNoContent());

        request.setAction("");
        mockMvc.perform(post("/api/servers/1/audit-logs").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void kanbanPermissionEndpoints_coverHappyAndUnhappy() throws Exception {
        KanbanPermissionRequest request = new KanbanPermissionRequest();
        request.setKey("EDIT_TASK");
        request.setName("Edit Task");
        request.setCategory("TASK");
        request.setDescription("Allows editing");

        KanbanPermission permission = kanbanPermission(11);
        when(kanbanPermissionService.create(any(KanbanPermission.class))).thenReturn(permission);
        when(kanbanPermissionService.findAll()).thenReturn(List.of(permission));
        when(kanbanPermissionService.findById(11)).thenReturn(Optional.of(permission));
        when(kanbanPermissionService.update(any(KanbanPermission.class))).thenReturn(permission);

        mockMvc.perform(post("/api/servers/1/permissions/catalog").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/permissions/catalog").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/permissions/catalog/11").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/permissions/catalog/11").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/permissions/catalog/11").param("userId", "10"))
                .andExpect(status().isNoContent());

        when(kanbanPermissionService.findById(404)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/servers/1/permissions/catalog/404").param("userId", "10"))
                .andExpect(status().isNotFound());
    }

    @Test
    void permissionEndpoints_coverHappyAndUnhappy() throws Exception {
        PermissionRequest request = new PermissionRequest();
        request.setScopeType("BOARD");
        request.setScopeId(100L);
        request.setSubjectType("ROLE");
        request.setSubjectId(20L);
        request.setKanbanPermissionId(11);
        request.setState("ALLOW");
        request.setPriority(100);
        request.setIsImmutable(false);

        Permission permission = permission(33L);
        KanbanPermission kanbanPermission = kanbanPermission(11);

        when(kanbanPermissionService.findById(11)).thenReturn(Optional.of(kanbanPermission));
        when(permissionService.create(any(Permission.class))).thenReturn(permission);
        when(permissionService.findAll()).thenReturn(List.of(permission));
        when(resourceValidator.requirePermissionInServer(33L, 1L)).thenReturn(permission);
        when(permissionService.update(any(Permission.class))).thenReturn(permission);

        mockMvc.perform(post("/api/servers/1/permissions").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/permissions").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/permissions/33").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/permissions/33").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/permissions/33").param("userId", "10"))
                .andExpect(status().isNoContent());

        when(kanbanPermissionService.findById(999)).thenReturn(Optional.empty());
        request.setKanbanPermissionId(999);
        mockMvc.perform(post("/api/servers/1/permissions").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void memberRoleEndpoints_coverHappyAndUnhappy() throws Exception {
        MemberRoleRequest request = new MemberRoleRequest();
        request.setServerMemberId(55L);
        request.setRoleId(20L);

        ServerMember serverMember = serverMember(55L);
        Role role = role(20L);
        MemberRole memberRole = memberRole(66L, serverMember, role);

        when(resourceValidator.requireServerMemberInServer(55L, 1L)).thenReturn(serverMember);
        when(resourceValidator.requireRoleInServer(20L, 1L)).thenReturn(role);
        when(memberRoleService.create(any(MemberRole.class))).thenReturn(memberRole);
        when(memberRoleService.findByServerMemberId(55L)).thenReturn(List.of(memberRole));
        when(resourceValidator.requireMemberRoleInServer(66L, 1L)).thenReturn(memberRole);
        when(memberRoleService.update(any(MemberRole.class))).thenReturn(memberRole);

        mockMvc.perform(post("/api/servers/1/members/55/roles").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/members/55/roles").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/members/55/roles/66").param("userId", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/members/55/roles/66").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/members/55/roles/66").param("userId", "10"))
                .andExpect(status().isNoContent());

        when(resourceValidator.requireRoleInServer(999L, 1L))
                .thenThrow(new ResourceNotFoundException("Role", "roleId", 999L));
        request.setRoleId(999L);
        mockMvc.perform(post("/api/servers/1/members/55/roles").param("userId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    private static User user(Long id) {
        User user = new User();
        user.setUserId(id);
        user.setUsername("u" + id);
        return user;
    }

    private static Server server(Long id) {
        Server server = new Server();
        server.setServerId(id);
        server.setName("server");
        server.setOwner(user(1L));
        return server;
    }

    private static Board board(Long id) {
        Board board = new Board();
        board.setBoardId(id);
        board.setServer(server(1L));
        board.setName("board");
        board.setCreatedBy(user(10L));
        return board;
    }

    private static AuditLog auditLog(Long id) {
        AuditLog log = new AuditLog();
        log.setLogId(id);
        log.setServer(server(1L));
        log.setBoard(board(100L));
        log.setUser(user(10L));
        log.setAction("ACTION");
        return log;
    }

    private static KanbanPermission kanbanPermission(Integer id) {
        KanbanPermission permission = new KanbanPermission();
        permission.setPermissionId(id);
        permission.setKey("EDIT_TASK");
        permission.setName("Edit Task");
        permission.setCategory("TASK");
        return permission;
    }

    private static Permission permission(Long id) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setScopeType("BOARD");
        permission.setScopeId(100L);
        permission.setSubjectType("ROLE");
        permission.setSubjectId(20L);
        permission.setKanbanPermission(kanbanPermission(11));
        permission.setState("ALLOW");
        permission.setPriority(10);
        return permission;
    }

    private static ServerMember serverMember(Long id) {
        ServerMember member = new ServerMember();
        member.setId(id);
        member.setServer(server(1L));
        member.setUser(user(10L));
        return member;
    }

    private static Role role(Long id) {
        Role role = new Role();
        role.setRoleId(id);
        role.setServer(server(1L));
        role.setName("role");
        return role;
    }

    private static MemberRole memberRole(Long id, ServerMember serverMember, Role role) {
        MemberRole memberRole = new MemberRole();
        memberRole.setId(id);
        memberRole.setServerMember(serverMember);
        memberRole.setRole(role);
        return memberRole;
    }
}
