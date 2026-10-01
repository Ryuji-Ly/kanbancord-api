package com.kanbancord_api.unit.service;

import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import com.kanbancord_api.permission.DefaultPermissionRules;
import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.DiscordPermissionParser;
import com.kanbancord_api.permission.KanbanPermission;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionEvaluationService.Decision;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.permission.PermissionRank;
import com.kanbancord_api.permission.PermissionRepository;
import com.kanbancord_api.permission.PermissionResolver;
import com.kanbancord_api.permission.PermissionSnapshot;
import com.kanbancord_api.server.MemberRoleRepository;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.ServerMember;
import com.kanbancord_api.server.ServerMemberRepository;
import com.kanbancord_api.server.ServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionEvaluationServiceTest {

    private static final long USER = 99L;
    private static final long ROLE = 88L;
    private static final long OTHER_ROLE = 77L;
    private static final long SERVER = 10L;
    private static final long BOARD = 55L;
    private static final long VIEW_CHANNEL = DiscordPermissionFlag.VIEW_CHANNEL.getBit();
    private static final long MANAGE_MESSAGES = DiscordPermissionFlag.MANAGE_MESSAGES.getBit();

    /** Resolution rules, exercised directly on in-memory snapshots. */
    @Nested
    class Resolver {

        // ── Within a layer, DENY beats ALLOW ──────────────────────────────────

        @Test
        void discordLayer_denyBeatsAllow() {
            PermissionSnapshot snapshot = snapshot(Set.of(), Set.of(VIEW_CHANNEL, MANAGE_MESSAGES), List.of(), List.of(
                    serverRule(1L, "EDIT_TASK", "ALLOW", "DISCORD_PERMISSION", MANAGE_MESSAGES),
                    serverRule(2L, "EDIT_TASK", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL)));

            Decision decision = resolve(snapshot, "EDIT_TASK");

            assertFalse(decision.allowed());
            assertEquals(2L, decision.sourcePermissionId());
        }

        @Test
        void roleLayer_denyBeatsAllow_acrossRoles() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE, OTHER_ROLE), Set.of(), List.of(), List.of(
                    serverRule(1L, "CREATE_TASK", "ALLOW", "ROLE", ROLE),
                    serverRule(2L, "CREATE_TASK", "DENY", "ROLE", OTHER_ROLE)));

            assertFalse(resolve(snapshot, "CREATE_TASK").allowed());
        }

        @Test
        void trace_listsEveryMatchingLayer_lastOneDecides() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE), Set.of(VIEW_CHANNEL), List.of(
                    boardRule(3L, "EDIT_TASK", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL)), List.of(
                    serverRule(1L, "EDIT_TASK", "ALLOW", "ROLE", ROLE),
                    serverRule(2L, "EDIT_TASK", "ALLOW", "USER", USER)));

            List<PermissionResolver.Layer> layers = PermissionResolver.trace(snapshot, "EDIT_TASK");

            assertEquals(3, layers.size());
            assertEquals("BOARD", layers.get(2).scopeType());
            assertEquals(3L, layers.get(2).decider().getId());
            // A board rule for a Discord permission beats a server rule for the person.
            assertFalse(resolve(snapshot, "EDIT_TASK").allowed());
        }

        @Test
        void noOneInParticular_matchesNoPersonRules() {
            PermissionSnapshot snapshot = new PermissionSnapshot(null, true, Set.of(ROLE), Set.of(), List.of(), List.of(
                    serverRule(1L, "EDIT_TASK", "ALLOW", "USER", USER)));

            assertFalse(resolve(snapshot, "EDIT_TASK").allowed());
            assertTrue(PermissionResolver.trace(snapshot, "EDIT_TASK").isEmpty());
        }

        // ── Layer order: Discord bits → roles → user, later overrides earlier ────

        @Test
        void roleLayer_overridesDiscordLayer_inBothDirections() {
            PermissionSnapshot allowedByRole = snapshot(Set.of(ROLE), Set.of(VIEW_CHANNEL), List.of(), List.of(
                    serverRule(1L, "EDIT_TASK", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL),
                    serverRule(2L, "EDIT_TASK", "ALLOW", "ROLE", ROLE)));
            assertTrue(resolve(allowedByRole, "EDIT_TASK").allowed());

            PermissionSnapshot deniedByRole = snapshot(Set.of(ROLE), Set.of(VIEW_CHANNEL), List.of(), List.of(
                    serverRule(1L, "EDIT_TASK", "ALLOW", "DISCORD_PERMISSION", VIEW_CHANNEL),
                    serverRule(2L, "EDIT_TASK", "DENY", "ROLE", ROLE)));
            Decision decision = resolve(deniedByRole, "EDIT_TASK");
            assertFalse(decision.allowed());
            assertEquals("ROLE", decision.sourceTier());
        }

        @Test
        void userLayer_overridesRoleAndDiscordLayers() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE), Set.of(MANAGE_MESSAGES), List.of(), List.of(
                    serverRule(1L, "EDIT_TASK", "ALLOW", "DISCORD_PERMISSION", MANAGE_MESSAGES),
                    serverRule(2L, "EDIT_TASK", "ALLOW", "ROLE", ROLE),
                    serverRule(3L, "EDIT_TASK", "DENY", "USER", USER)));

            Decision decision = resolve(snapshot, "EDIT_TASK");

            assertFalse(decision.allowed());
            assertEquals("USER", decision.sourceTier());
            assertEquals(3L, decision.sourcePermissionId());
        }

        @Test
        void priority_doesNotAffectResolution() {
            Permission highPriorityAllow = serverRule(1L, "EDIT_TASK", "ALLOW", "ROLE", ROLE);
            highPriorityAllow.setPriority(10_000);
            Permission lowPriorityDeny = serverRule(2L, "EDIT_TASK", "DENY", "ROLE", OTHER_ROLE);
            lowPriorityDeny.setPriority(1);

            PermissionSnapshot snapshot = snapshot(Set.of(ROLE, OTHER_ROLE), Set.of(), List.of(),
                    List.of(highPriorityAllow, lowPriorityDeny));

            assertFalse(resolve(snapshot, "EDIT_TASK").allowed());
        }

        // ── Board scope overrides server scope ─────────────────────────────────

        @Test
        void boardWithoutRulesForKey_inheritsServerResult() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE), Set.of(),
                    List.of(boardRule(10L, "OTHER_KEY", "DENY", "ROLE", ROLE)),
                    List.of(serverRule(1L, "VIEW_BOARD", "ALLOW", "ROLE", ROLE)));

            Decision decision = resolve(snapshot, "VIEW_BOARD");

            assertTrue(decision.allowed());
            assertEquals("SERVER", decision.sourceScopeType());
        }

        @Test
        void boardRule_overridesServerRule_forSameSubject() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE), Set.of(),
                    List.of(boardRule(10L, "VIEW_BOARD", "DENY", "ROLE", ROLE)),
                    List.of(serverRule(1L, "VIEW_BOARD", "ALLOW", "ROLE", ROLE)));

            Decision decision = resolve(snapshot, "VIEW_BOARD");

            assertFalse(decision.allowed());
            assertEquals("BOARD", decision.sourceScopeType());
        }

        @Test
        void boardDiscordRule_overridesServerUserRule() {
            // Board scope always wins over server scope, even against a server-level USER rule.
            PermissionSnapshot snapshot = snapshot(Set.of(), Set.of(VIEW_CHANNEL),
                    List.of(boardRule(10L, "EDIT_TASK", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL)),
                    List.of(serverRule(1L, "EDIT_TASK", "ALLOW", "USER", USER)));

            assertFalse(resolve(snapshot, "EDIT_TASK").allowed());
        }

        @Test
        void boardLayers_followSameOrder_userOverridesRole() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE), Set.of(),
                    List.of(
                            boardRule(10L, "VIEW_BOARD", "DENY", "ROLE", ROLE),
                            boardRule(11L, "VIEW_BOARD", "ALLOW", "USER", USER)),
                    List.of());

            assertTrue(resolve(snapshot, "VIEW_BOARD").allowed());
        }

        @Test
        void privateBoard_hidesBoardFromEveryoneExceptAllowedRole() {
            List<Permission> serverRules = List.of(
                    serverRule(1L, "VIEW_BOARD", "ALLOW", "DISCORD_PERMISSION", VIEW_CHANNEL));
            List<Permission> boardRules = List.of(
                    boardRule(10L, "VIEW_BOARD", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL),
                    boardRule(11L, "VIEW_BOARD", "ALLOW", "ROLE", ROLE));

            PermissionSnapshot regularMember = snapshot(Set.of(), Set.of(VIEW_CHANNEL), boardRules, serverRules);
            PermissionSnapshot staffMember = snapshot(Set.of(ROLE), Set.of(VIEW_CHANNEL), boardRules, serverRules);

            assertFalse(resolve(regularMember, "VIEW_BOARD").allowed());
            assertTrue(resolve(staffMember, "VIEW_BOARD").allowed());
            // Outside that board (server scope) the same member still has VIEW_BOARD.
            assertTrue(resolve(regularMember.withBoardRules(List.of()), "VIEW_BOARD").allowed());
        }

        // ── ADMIN, membership and defaults ─────────────────────────────────────

        @Test
        void adminGrant_allowsEveryKey_evenOverDenies() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE), Set.of(),
                    List.of(boardRule(10L, "DELETE_BOARD", "DENY", "ROLE", ROLE)),
                    List.of(
                            serverRule(30L, "ADMIN", "ALLOW", "ROLE", ROLE),
                            serverRule(31L, "DELETE_BOARD", "DENY", "USER", USER)));

            Decision decision = resolve(snapshot, "DELETE_BOARD");

            assertTrue(decision.allowed());
            assertEquals("ADMIN", decision.sourceTier());
            assertEquals(30L, decision.sourcePermissionId());
        }

        @Test
        void nonMember_getsNoRoleOrDiscordGrants() {
            PermissionSnapshot snapshot = new PermissionSnapshot(
                    USER,
                    false,
                    Set.of(ROLE),
                    Set.of(DiscordPermissionFlag.ADMINISTRATOR.getBit()),
                    List.of(),
                    List.of(
                            serverRule(50L, "VIEW_BOARD", "ALLOW", "ROLE", ROLE),
                            serverRule(51L, "ADMIN", "ALLOW", "DISCORD_PERMISSION",
                                    DiscordPermissionFlag.ADMINISTRATOR.getBit())));

            assertFalse(resolve(snapshot, "VIEW_BOARD").allowed());
        }

        @Test
        void noMatchingRule_isDenied() {
            assertEquals(Decision.NONE, resolve(snapshot(Set.of(), Set.of(), List.of(), List.of()), "EDIT_TASK"));
        }

        @Test
        void unknownState_isTreatedAsDeny() {
            PermissionSnapshot snapshot = snapshot(Set.of(), Set.of(), List.of(),
                    List.of(serverRule(1L, "EDIT_TASK", "MAYBE", "USER", USER)));

            assertFalse(resolve(snapshot, "EDIT_TASK").allowed());
        }

        // ── Simulated changes and rank ─────────────────────────────────────────

        @Test
        void withRuleChange_replacesRemovesAndAddsRules() {
            Permission allow = serverRule(1L, "EDIT_TASK", "ALLOW", "USER", USER);
            Permission deny = serverRule(1L, "EDIT_TASK", "DENY", "USER", USER);
            PermissionSnapshot snapshot = snapshot(Set.of(), Set.of(), List.of(), List.of(allow));

            assertFalse(resolve(snapshot.withRuleChange(allow, deny), "EDIT_TASK").allowed());
            assertFalse(resolve(snapshot.withRuleChange(allow, null), "EDIT_TASK").allowed());
            assertTrue(resolve(snapshot.withRuleChange(null,
                    boardRule(null, "VIEW_BOARD", "ALLOW", "USER", USER)), "VIEW_BOARD").allowed());
        }

        @Test
        void effectiveRank_isHighestAllowedCatalogRank() {
            PermissionSnapshot snapshot = snapshot(Set.of(ROLE), Set.of(), List.of(), List.of(
                    serverRule(1L, "VIEW_BOARD", "ALLOW", "ROLE", ROLE),
                    serverRule(2L, "EDIT_BOARD_PERMISSIONS", "ALLOW", "ROLE", ROLE)));

            assertEquals(PermissionRank.BOARD_MANAGE, PermissionResolver.effectiveRank(snapshot));
        }
    }

    /** Snapshot loading: what the service reads from the database. */
    @Nested
    @ExtendWith(MockitoExtension.class)
    class Loading {

        @Mock
        private PermissionRepository permissionRepository;
        @Mock
        private ServerMemberRepository serverMemberRepository;
        @Mock
        private MemberRoleRepository memberRoleRepository;
        @Mock
        private ServerRepository serverRepository;
        @Mock
        private ServerFeatureService serverFeatureService;

        private PermissionEvaluationService service;

        @BeforeEach
        void setUp() {
            service = new PermissionEvaluationService(
                    permissionRepository,
                    serverMemberRepository,
                    memberRoleRepository,
                    serverRepository,
                    new DiscordPermissionParser(),
                    serverFeatureService);
            lenient().when(serverFeatureService.isEnabled(SERVER, Feature.PERMISSIONS)).thenReturn(true);
            lenient().when(permissionRepository.findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(
                    eq("SERVER"), eq(SERVER))).thenReturn(List.of());
        }

        @Test
        void owner_withoutRoles_getsAdministratorFlag() {
            memberWithRoles();
            when(serverRepository.findOwnerIdByServerId(SERVER)).thenReturn(Optional.of(USER));

            PermissionSnapshot snapshot = service.loadSnapshot(SERVER, null, USER);

            assertTrue(snapshot.member());
            assertTrue(snapshot.discordFlagBits().contains(DiscordPermissionFlag.ADMINISTRATOR.getBit()));
        }

        @Test
        void discordFlags_areAggregatedAcrossRoles() {
            memberWithRoles(role(1L, VIEW_CHANNEL), role(2L, MANAGE_MESSAGES));
            when(serverRepository.findOwnerIdByServerId(SERVER)).thenReturn(Optional.of(1234L));

            PermissionSnapshot snapshot = service.loadSnapshot(SERVER, null, USER);

            assertEquals(Set.of(1L, 2L), snapshot.roleIds());
            assertEquals(Set.of(VIEW_CHANNEL, MANAGE_MESSAGES), snapshot.discordFlagBits());
        }

        @Test
        void nonMember_loadsNoRolesAndNoBoardRulesAtServerScope() {
            when(serverMemberRepository.findByServer_ServerIdAndUser_UserId(SERVER, USER))
                    .thenReturn(Optional.empty());

            PermissionSnapshot snapshot = service.loadSnapshot(SERVER, null, USER);

            assertFalse(snapshot.member());
            assertTrue(snapshot.roleIds().isEmpty());
            verify(permissionRepository, never()).findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(
                    eq("BOARD"), eq(BOARD));
        }

        @Test
        void resolveAll_evaluatesManyKeysFromOneSnapshot() {
            memberWithRoles(role(ROLE, 0L));
            when(serverRepository.findOwnerIdByServerId(SERVER)).thenReturn(Optional.empty());
            when(permissionRepository.findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc("SERVER", SERVER))
                    .thenReturn(List.of(serverRule(1L, "VIEW_BOARD", "ALLOW", "ROLE", ROLE)));
            when(permissionRepository.findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc("BOARD", BOARD))
                    .thenReturn(List.of(boardRule(2L, "EDIT_TASK", "ALLOW", "ROLE", ROLE)));

            Map<String, Decision> decisions = service.resolveAll(SERVER, BOARD, USER,
                    List.of("VIEW_BOARD", "EDIT_TASK", "DELETE_BOARD"));

            assertTrue(decisions.get("VIEW_BOARD").allowed());
            assertTrue(decisions.get("EDIT_TASK").allowed());
            assertFalse(decisions.get("DELETE_BOARD").allowed());
            verify(serverMemberRepository).findByServer_ServerIdAndUser_UserId(SERVER, USER);
        }

        @Test
        void filterAllowedBoards_appliesEachBoardsOverrides() {
            memberWithRoles(role(ROLE, 0L));
            when(serverRepository.findOwnerIdByServerId(SERVER)).thenReturn(Optional.empty());
            when(permissionRepository.findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc("SERVER", SERVER))
                    .thenReturn(List.of(serverRule(1L, "VIEW_BOARD", "ALLOW", "ROLE", ROLE)));
            Permission hideBoardTwo = boardRule(2L, "VIEW_BOARD", "DENY", "ROLE", ROLE);
            hideBoardTwo.setScopeId(2L);
            when(permissionRepository.findByScopeTypeAndScopeIdIn(eq("BOARD"), anyCollection()))
                    .thenReturn(List.of(hideBoardTwo));

            Set<Long> visible = service.filterAllowedBoards(SERVER, List.of(1L, 2L, 3L), USER, "VIEW_BOARD");

            assertEquals(Set.of(1L, 3L), visible);
        }

        @Test
        void customPermissionsOff_appliesTheDefaults_andNoStoredRules() {
            when(serverFeatureService.isEnabled(SERVER, Feature.PERMISSIONS)).thenReturn(false);
            memberWithRoles(role(ROLE, VIEW_CHANNEL | DiscordPermissionFlag.SEND_MESSAGES.getBit()));
            when(serverRepository.findOwnerIdByServerId(SERVER)).thenReturn(Optional.empty());

            PermissionSnapshot snapshot = service.loadSnapshot(SERVER, BOARD, USER);

            assertEquals(DefaultPermissionRules.forServer(SERVER).size(), snapshot.serverRules().size());
            assertTrue(snapshot.boardRules().isEmpty());
            assertTrue(resolve(snapshot, "CREATE_TASK").allowed());
            assertFalse(resolve(snapshot, "EDIT_TASK").allowed());
            verify(permissionRepository, never()).findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(eq("SERVER"), eq(SERVER));
            verify(permissionRepository, never()).findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(eq("BOARD"), eq(BOARD));
        }

        @Test
        void customPermissionsOff_boardsHaveNoRulesOfTheirOwn() {
            when(serverFeatureService.isEnabled(SERVER, Feature.PERMISSIONS)).thenReturn(false);
            memberWithRoles(role(ROLE, VIEW_CHANNEL));
            when(serverRepository.findOwnerIdByServerId(SERVER)).thenReturn(Optional.empty());

            Set<Long> visible = service.filterAllowedBoards(SERVER, List.of(1L, 2L), USER, "VIEW_BOARD");

            assertEquals(Set.of(1L, 2L), visible);
            verify(permissionRepository, never()).findByScopeTypeAndScopeIdIn(eq("BOARD"), anyCollection());
        }

        private void memberWithRoles(Role... roles) {
            ServerMember member = new ServerMember();
            member.setId(7L);
            when(serverMemberRepository.findByServer_ServerIdAndUser_UserId(SERVER, USER))
                    .thenReturn(Optional.of(member));
            when(memberRoleRepository.findRolesWithEveryone(7L, SERVER)).thenReturn(List.of(roles));
        }
    }

    private static Decision resolve(PermissionSnapshot snapshot, String key) {
        return PermissionResolver.resolve(snapshot, key);
    }

    private static PermissionSnapshot snapshot(
            Set<Long> roleIds, Set<Long> discordBits, List<Permission> boardRules, List<Permission> serverRules) {
        return new PermissionSnapshot(USER, true, roleIds, discordBits, boardRules, serverRules);
    }

    private static Role role(Long id, long discordPermissions) {
        Role role = new Role();
        role.setRoleId(id);
        role.setDiscordPermissions(discordPermissions);
        return role;
    }

    private static Permission serverRule(Long id, String key, String state, String subjectType, Long subjectId) {
        return rule(id, key, state, "SERVER", SERVER, subjectType, subjectId);
    }

    private static Permission boardRule(Long id, String key, String state, String subjectType, Long subjectId) {
        return rule(id, key, state, "BOARD", BOARD, subjectType, subjectId);
    }

    static Permission rule(Long id, String key, String state, String scopeType, Long scopeId,
            String subjectType, Long subjectId) {
        KanbanPermission kanbanPermission = new KanbanPermission();
        kanbanPermission.setKey(key);

        Permission permission = new Permission();
        permission.setId(id);
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(state);
        permission.setPriority(100);
        permission.setScopeType(scopeType);
        permission.setScopeId(scopeId);
        permission.setSubjectType(subjectType);
        permission.setSubjectId(subjectId);
        return permission;
    }
}
