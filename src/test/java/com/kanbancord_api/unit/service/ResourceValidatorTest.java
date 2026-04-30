package com.kanbancord_api.unit.service;

import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.service.AuditLogService;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.BoardService;
import com.kanbancord_api.service.BusinessValidationService;
import com.kanbancord_api.service.LabelService;
import com.kanbancord_api.service.MemberRoleService;
import com.kanbancord_api.service.PermissionService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.RoleService;
import com.kanbancord_api.service.ServerMemberService;
import com.kanbancord_api.service.TaskAssignmentService;
import com.kanbancord_api.service.TaskCommentService;
import com.kanbancord_api.service.TaskLabelService;
import com.kanbancord_api.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceValidatorTest {

    @Mock
    private BoardService boardService;
    @Mock
    private BoardColumnService boardColumnService;
    @Mock
    private TaskService taskService;
    @Mock
    private LabelService labelService;
    @Mock
    private TaskAssignmentService taskAssignmentService;
    @Mock
    private TaskCommentService taskCommentService;
    @Mock
    private TaskLabelService taskLabelService;
    @Mock
    private RoleService roleService;
    @Mock
    private ServerMemberService serverMemberService;
    @Mock
    private MemberRoleService memberRoleService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private BusinessValidationService businessValidationService;

    private ResourceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ResourceValidator(
                boardService,
                boardColumnService,
                taskService,
                labelService,
                taskAssignmentService,
                taskCommentService,
                taskLabelService,
                roleService,
                serverMemberService,
                memberRoleService,
                auditLogService,
                permissionService,
                businessValidationService);
    }

    @Test
    void validatePathMatchesRequestId_throwsBadRequest_whenIdsMismatch() {
        assertThrows(BadRequestException.class, () -> validator.validatePathMatchesRequestId("boardId", 1L, 2L));
    }

    @Test
    void validatePathMatchesRequestId_allowsWhenRequestIdIsNull_edgeCase() {
        assertDoesNotThrow(() -> validator.validatePathMatchesRequestId("boardId", 1L, null));
    }

    @Test
    void requireBoardInServer_throwsNotFound_whenBoardMissing() {
        when(boardService.findByIdAndServerId(10L, 20L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> validator.requireBoardInServer(10L, 20L));
    }

    @Test
    void validateBoardNotArchived_throwsBadRequest_whenBoardArchived() {
        Board board = new Board();
        board.setIsArchived(true);

        assertThrows(BadRequestException.class, () -> validator.validateBoardNotArchived(board));
    }

    @Test
    void validateBoardNotArchived_allowsActiveBoard() {
        Board board = new Board();
        board.setIsArchived(false);

        assertDoesNotThrow(() -> validator.validateBoardNotArchived(board));
    }

    @Test
    void validatePermissionScopeBelongsToServer_throwsBadRequest_whenServerScopeIdMismatch() {
        assertThrows(BadRequestException.class,
                () -> validator.validatePermissionScopeBelongsToServer("SERVER", 999L, 1L));
    }

    @Test
    void validatePermissionScopeBelongsToServer_allowsBoardScope_whenBoardExists() {
        Board board = new Board();
        when(boardService.findByIdAndServerId(5L, 1L)).thenReturn(Optional.of(board));

        assertDoesNotThrow(() -> validator.validatePermissionScopeBelongsToServer("BOARD", 5L, 1L));
    }

    @Test
    void validatePermissionSubjectBelongsToServer_throwsBadRequest_whenUserNotInServer() {
        when(serverMemberService.existsByServerIdAndUserId(1L, 77L)).thenReturn(false);

        assertThrows(BadRequestException.class,
                () -> validator.validatePermissionSubjectBelongsToServer("USER", 77L, 1L));
    }

    @Test
    void validatePermissionSubjectBelongsToServer_allowsRole_whenRoleInServer() {
        Server server = new Server();
        server.setServerId(1L);
        Role role = new Role();
        role.setRoleId(9L);
        role.setServer(server);

        when(roleService.findById(9L)).thenReturn(Optional.of(role));

        assertDoesNotThrow(() -> validator.validatePermissionSubjectBelongsToServer("ROLE", 9L, 1L));
    }

    @Test
    void permissionBelongsToServer_returnsTrue_whenServerScopeMatches() {
        Permission permission = new Permission();
        permission.setScopeType("SERVER");
        permission.setScopeId(1L);

        assertTrue(validator.permissionBelongsToServer(permission, 1L));
    }

    @Test
    void permissionBelongsToServer_returnsFalse_whenUnsupportedScope_edgeCase() {
        Permission permission = new Permission();
        permission.setScopeType("UNKNOWN");
        permission.setScopeId(1L);

        assertFalse(validator.permissionBelongsToServer(permission, 1L));
    }
}
