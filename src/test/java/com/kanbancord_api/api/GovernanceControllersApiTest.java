package com.kanbancord_api.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.audit.AuditLog;
import com.kanbancord_api.audit.AuditLogController;
import com.kanbancord_api.audit.AuditLogService;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.GlobalExceptionHandler;
import com.kanbancord_api.permission.KanbanPermission;
import com.kanbancord_api.permission.KanbanPermissionController;
import com.kanbancord_api.permission.KanbanPermissionService;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionController;
import com.kanbancord_api.permission.PermissionEscalationGuardService;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.permission.PermissionRequest;
import com.kanbancord_api.permission.PermissionRuleCommands;
import com.kanbancord_api.permission.PermissionService;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        PermissionController.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import({ GlobalExceptionHandler.class, PermissionRuleCommands.class })
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
    private Authorizer authorizer;
        @MockitoBean
    private ResourceValidator resourceValidator;
        @MockitoBean
    private KanbanPermissionService kanbanPermissionService;
        @MockitoBean
    private PermissionService permissionService;
        @MockitoBean
    private PermissionEvaluationService permissionEvaluationService;
        @MockitoBean
    private PermissionEscalationGuardService permissionEscalationGuardService;
    
    @Test
    void auditLogEndpoints_areReadOnlyAndRequireViewAuditLog() throws Exception {
        AuditLog log = auditLog(900L);
        when(auditLogService.findByServerIdOrdered(1L)).thenReturn(List.of(log));
        when(resourceValidator.requireAuditLogInServer(900L, 1L)).thenReturn(log);

        mockMvc.perform(get("/api/servers/1/audit-logs").with(asUser(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].logId").value(900));

        mockMvc.perform(get("/api/servers/1/audit-logs/900").with(asUser(10L)))
                .andExpect(status().isOk());

        verify(authorizer, times(2)).requireServerPermission(10L, 1L, "VIEW_AUDIT_LOG");

        doThrow(new AccessDeniedException("denied"))
                .when(authorizer).requireServerPermission(11L, 1L, "VIEW_AUDIT_LOG");
        mockMvc.perform(get("/api/servers/1/audit-logs").with(asUser(11L)))
                .andExpect(status().isForbidden());

        // Audit entries are append-only and written by the server, never by clients.
        mockMvc.perform(post("/api/servers/1/audit-logs").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/servers/1/audit-logs/900").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/servers/1/audit-logs/900").with(asUser(10L)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void kanbanPermissionCatalog_isReadOnly() throws Exception {
        KanbanPermission permission = kanbanPermission(11);
        when(kanbanPermissionService.findAll()).thenReturn(List.of(permission));

        mockMvc.perform(get("/api/servers/1/permissions/catalog").with(asUser(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("EDIT_TASK"));

        // The catalog is global and defined in code; server members must not be able to mutate it.
        mockMvc.perform(post("/api/servers/1/permissions/catalog").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/servers/1/permissions/catalog/11").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete("/api/servers/1/permissions/catalog/11").with(asUser(10L)))
                .andExpect(status().is4xxClientError());
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
        when(permissionService.findAllInServer(1L)).thenReturn(List.of(permission));
        when(resourceValidator.requirePermissionInServer(33L, 1L)).thenReturn(permission);
        when(permissionService.update(any(Permission.class))).thenReturn(permission);

        mockMvc.perform(post("/api/servers/1/permissions").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/servers/1/permissions").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/servers/1/permissions/33").with(asUser(10L)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/servers/1/permissions/33").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/servers/1/permissions/33").with(asUser(10L)))
                .andExpect(status().isNoContent());

        when(kanbanPermissionService.findById(999)).thenReturn(Optional.empty());
        request.setKanbanPermissionId(999);
        mockMvc.perform(post("/api/servers/1/permissions").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluatingOwnPermissions_needsOnlyMembership_othersNeedManagePermissions() throws Exception {
        when(permissionEvaluationService.resolveAll(any(), any(), any(), any()))
                .thenReturn(Map.of("EDIT_TASK", PermissionEvaluationService.Decision.NONE));

        mockMvc.perform(get("/api/servers/1/permissions/evaluate-batch").with(asUser(10L))
                .param("permissionKey", "EDIT_TASK"))
                .andExpect(status().isOk());
        verify(authorizer).requireUserInServer(10L, 1L);
        verify(authorizer, never()).requireServerPermission(10L, 1L, "MANAGE_SERVER_PERMISSIONS");

        mockMvc.perform(get("/api/servers/1/permissions/evaluate-batch").with(asUser(10L))
                .param("permissionKey", "EDIT_TASK")
                .param("targetUserId", "11"))
                .andExpect(status().isOk());
        verify(authorizer).requireServerPermission(10L, 1L, "MANAGE_SERVER_PERMISSIONS");
        verify(permissionEvaluationService).resolveAll(1L, null, 11L, List.of("EDIT_TASK"));

        doThrow(new AccessDeniedException("denied"))
                .when(authorizer).requireServerPermission(12L, 1L, "MANAGE_SERVER_PERMISSIONS");
        mockMvc.perform(get("/api/servers/1/permissions/evaluate-batch").with(asUser(12L))
                .param("permissionKey", "EDIT_TASK")
                .param("targetUserId", "11"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createdPermissionRules_areNeverImmutable() throws Exception {
        PermissionRequest request = new PermissionRequest();
        request.setScopeType("SERVER");
        request.setScopeId(1L);
        request.setSubjectType("ROLE");
        request.setSubjectId(20L);
        request.setKanbanPermissionId(11);
        request.setState("DENY");
        request.setPriority(100);
        request.setIsImmutable(true);

        when(kanbanPermissionService.findById(11)).thenReturn(Optional.of(kanbanPermission(11)));
        when(permissionService.create(any(Permission.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/servers/1/permissions").with(asUser(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isImmutable").value(false));
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
}
