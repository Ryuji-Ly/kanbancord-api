package com.kanbancord_api.unit.service;

import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionEscalationGuardService;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.permission.PermissionRepository;
import com.kanbancord_api.permission.PermissionSnapshot;
import static com.kanbancord_api.unit.service.PermissionEvaluationServiceTest.rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionEscalationGuardServiceTest {

    private static final long SERVER = 1L;
    private static final long BOARD = 55L;
    private static final long ACTOR = 99L;
    private static final long TARGET_USER = 42L;
    private static final long ACTOR_ROLE = 7L;
    private static final long TARGET_ROLE = 8L;
    private static final long VIEW_CHANNEL = 1L << 10;

    @Mock
    private PermissionEvaluationService permissionEvaluationService;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private ResourceValidator resourceValidator;

    private PermissionEscalationGuardService service;

    @BeforeEach
    void setUp() {
        service = new PermissionEscalationGuardService(permissionEvaluationService, permissionRepository,
                resourceValidator);
        lenient().when(resourceValidator.permissionBelongsToServer(any(), eq(SERVER))).thenReturn(true);
        lenient().when(permissionRepository.findBySubjectTypeAndSubjectId(any(), any())).thenReturn(List.of());
        lenient().when(permissionEvaluationService.loadSnapshot(eq(SERVER), any(), eq(TARGET_USER)))
                .thenReturn(snapshot(TARGET_USER, Set.of(), List.of(), List.of()));
    }

    // ── Server-scope rules ──────────────────────────────────────────────────

    @Test
    void serverRule_requiresServerManagementRank() {
        actorAtServer(serverRule(1L, "EDIT_TASK", "ALLOW", "ROLE", ACTOR_ROLE));

        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "VIEW_BOARD", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL)));
    }

    @Test
    void serverManager_canChangeLowerRankedKeys_butNotEqualOrHigher() {
        actorAtServer(serverRule(1L, "MANAGE_SERVER_PERMISSIONS", "ALLOW", "ROLE", ACTOR_ROLE));

        assertDoesNotThrow(() -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "CREATE_BOARD", "ALLOW", "DISCORD_PERMISSION", VIEW_CHANNEL)));
        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "MANAGE_SERVER_PERMISSIONS", "ALLOW", "DISCORD_PERMISSION", VIEW_CHANNEL)));
        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "ADMIN", "ALLOW", "DISCORD_PERMISSION", VIEW_CHANNEL)));
    }

    @Test
    void serverManager_cannotChangeRulesForEqualOrHigherRankedRole() {
        actorAtServer(serverRule(1L, "MANAGE_SERVER_PERMISSIONS", "ALLOW", "ROLE", ACTOR_ROLE));
        when(permissionRepository.findBySubjectTypeAndSubjectId("ROLE", TARGET_ROLE)).thenReturn(List.of(
                serverRule(2L, "MANAGE_SERVER_PERMISSIONS", "ALLOW", "ROLE", TARGET_ROLE)));

        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "EDIT_TASK", "DENY", "ROLE", TARGET_ROLE)));
    }

    @Test
    void serverManager_cannotChangeRulesForEqualOrHigherRankedUser() {
        actorAtServer(serverRule(1L, "MANAGE_SERVER_PERMISSIONS", "ALLOW", "ROLE", ACTOR_ROLE));
        when(permissionEvaluationService.loadSnapshot(SERVER, null, TARGET_USER)).thenReturn(snapshot(TARGET_USER,
                Set.of(), List.of(), List.of(serverRule(2L, "ADMIN", "ALLOW", "USER", TARGET_USER))));

        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "EDIT_TASK", "DENY", "USER", TARGET_USER)));
    }

    @Test
    void admin_canChangeAnything() {
        actorAtServer(serverRule(1L, "ADMIN", "ALLOW", "ROLE", ACTOR_ROLE));

        assertDoesNotThrow(() -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "MANAGE_SERVER_PERMISSIONS", "DENY", "ROLE", TARGET_ROLE)));
    }

    // ── Lockout protection ──────────────────────────────────────────────────

    @Test
    void change_thatRemovesActorsOwnManagement_isRejected() {
        Permission adminForOwnRole = serverRule(1L, "ADMIN", "ALLOW", "ROLE", ACTOR_ROLE);
        actorAtServer(adminForOwnRole);

        assertThrows(BadRequestException.class, () -> service.validateChange(ACTOR, SERVER, adminForOwnRole,
                serverRule(1L, "ADMIN", "DENY", "ROLE", ACTOR_ROLE)));
        assertThrows(BadRequestException.class, () -> service.validateChange(ACTOR, SERVER, adminForOwnRole, null));
    }

    @Test
    void change_isAllowed_whenActorKeepsManagementThroughAnotherRule() {
        Permission adminForOwnRole = serverRule(1L, "ADMIN", "ALLOW", "ROLE", ACTOR_ROLE);
        actorAtServer(adminForOwnRole,
                serverRule(2L, "MANAGE_SERVER_PERMISSIONS", "ALLOW", "USER", ACTOR));

        assertDoesNotThrow(() -> service.validateChange(ACTOR, SERVER, adminForOwnRole, null));
    }

    // ── Board-scope rules and EDIT_BOARD_PERMISSIONS ─────────────────────────

    @Test
    void boardPermissionEditor_canMakeBoardPrivate() {
        actorAtBoard(List.of(boardRule(10L, "EDIT_BOARD_PERMISSIONS", "ALLOW", "ROLE", ACTOR_ROLE)));

        assertDoesNotThrow(() -> service.validateChange(ACTOR, SERVER, null,
                boardRule(null, "VIEW_BOARD", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL)));
        assertDoesNotThrow(() -> service.validateChange(ACTOR, SERVER, null,
                boardRule(null, "VIEW_BOARD", "ALLOW", "ROLE", TARGET_ROLE)));
    }

    @Test
    void boardPermissionEditor_cannotGrantBoardManagementKeys() {
        actorAtBoard(List.of(boardRule(10L, "EDIT_BOARD_PERMISSIONS", "ALLOW", "ROLE", ACTOR_ROLE)));

        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                boardRule(null, "EDIT_BOARD_PERMISSIONS", "ALLOW", "ROLE", TARGET_ROLE)));
        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                boardRule(null, "DELETE_COLUMN", "ALLOW", "ROLE", TARGET_ROLE)));
    }

    @Test
    void boardPermissionEditor_cannotChangeServerRules() {
        // Holds EDIT_BOARD_PERMISSIONS on a board, but nothing at server scope.
        actorAtServer();

        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                serverRule(null, "VIEW_BOARD", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL)));
    }

    @Test
    void boardRuleChange_withoutEditBoardPermissions_isDenied() {
        actorAtBoard(List.of(boardRule(10L, "EDIT_TASK", "ALLOW", "ROLE", ACTOR_ROLE)));

        assertThrows(AccessDeniedException.class, () -> service.validateChange(ACTOR, SERVER, null,
                boardRule(null, "VIEW_BOARD", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL)));
    }

    @Test
    void serverManager_canChangeBoardRules() {
        when(permissionEvaluationService.loadSnapshot(SERVER, BOARD, ACTOR)).thenReturn(snapshot(ACTOR,
                Set.of(ACTOR_ROLE), List.of(),
                List.of(serverRule(1L, "MANAGE_SERVER_PERMISSIONS", "ALLOW", "ROLE", ACTOR_ROLE))));

        assertDoesNotThrow(() -> service.validateChange(ACTOR, SERVER, null,
                boardRule(null, "EDIT_COLUMN", "ALLOW", "ROLE", TARGET_ROLE)));
    }

    private void actorAtServer(Permission... serverRules) {
        lenient().when(permissionEvaluationService.loadSnapshot(SERVER, null, ACTOR))
                .thenReturn(snapshot(ACTOR, Set.of(ACTOR_ROLE), List.of(), List.of(serverRules)));
    }

    private void actorAtBoard(List<Permission> boardRules) {
        when(permissionEvaluationService.loadSnapshot(SERVER, BOARD, ACTOR))
                .thenReturn(snapshot(ACTOR, Set.of(ACTOR_ROLE), boardRules, List.of()));
    }

    private static PermissionSnapshot snapshot(long userId, Set<Long> roles, List<Permission> boardRules,
            List<Permission> serverRules) {
        return new PermissionSnapshot(userId, true, roles, Set.of(VIEW_CHANNEL), boardRules, serverRules);
    }

    private static Permission serverRule(Long id, String key, String state, String subjectType, Long subjectId) {
        return rule(id, key, state, "SERVER", SERVER, subjectType, subjectId);
    }

    private static Permission boardRule(Long id, String key, String state, String subjectType, Long subjectId) {
        return rule(id, key, state, "BOARD", BOARD, subjectType, subjectId);
    }
}
