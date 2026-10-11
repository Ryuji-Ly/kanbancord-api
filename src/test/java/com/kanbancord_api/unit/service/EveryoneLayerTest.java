package com.kanbancord_api.unit.service;

import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.KanbanPermission;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionEvaluationService.Decision;
import com.kanbancord_api.permission.PermissionResolver;
import com.kanbancord_api.permission.PermissionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @everyone is its own layer, beneath the other roles, as in Discord's channel permissions:
 * Discord permissions, then @everyone, then roles, then the person; server-wide first, then the board.
 */
class EveryoneLayerTest {

    private static final long SERVER = 10L;
    private static final long EVERYONE = SERVER;
    private static final long STAFF = 20L;
    private static final long TRIAL = 30L;
    private static final long USER = 99L;
    private static final long BOARD = 55L;
    private static final long VIEW_CHANNEL = DiscordPermissionFlag.VIEW_CHANNEL.getBit();

    @Test
    void denyEveryone_allowStaff_letsStaffIn_andKeepsEveryoneElseOut() {
        List<Permission> rules = List.of(
                server("VIEW_BOARD", "ALLOW", "DISCORD_PERMISSION", VIEW_CHANNEL),
                server("VIEW_BOARD", "DENY", "ROLE", EVERYONE),
                server("VIEW_BOARD", "ALLOW", "ROLE", STAFF));

        Decision staff = resolve(Set.of(EVERYONE, STAFF), rules, List.of(), "VIEW_BOARD");
        assertTrue(staff.allowed(), "a role's allow outweighs @everyone's deny");
        assertEquals("ROLE", staff.sourceTier());

        Decision member = resolve(Set.of(EVERYONE), rules, List.of(), "VIEW_BOARD");
        assertFalse(member.allowed(), "@everyone's deny outweighs the Discord permission's allow");
        assertEquals(PermissionResolver.TIER_EVERYONE, member.sourceTier());
    }

    @Test
    void aRoleDeny_stillBeatsAnotherRolesAllow_inTheRoleLayer() {
        List<Permission> rules = List.of(
                server("DELETE_TASK", "ALLOW", "ROLE", STAFF),
                server("DELETE_TASK", "DENY", "ROLE", TRIAL));

        assertFalse(resolve(Set.of(EVERYONE, STAFF, TRIAL), rules, List.of(), "DELETE_TASK").allowed());
    }

    @Test
    void allowEveryone_outweighsADiscordPermissionDeny() {
        List<Permission> rules = List.of(
                server("CREATE_TASK", "DENY", "DISCORD_PERMISSION", VIEW_CHANNEL),
                server("CREATE_TASK", "ALLOW", "ROLE", EVERYONE));

        assertTrue(resolve(Set.of(EVERYONE), rules, List.of(), "CREATE_TASK").allowed());
    }

    @Test
    void aBoardsEveryoneRule_outweighsServerWideRoleRules() {
        List<Permission> serverRules = List.of(server("VIEW_BOARD", "ALLOW", "ROLE", STAFF));
        List<Permission> boardRules = List.of(board("VIEW_BOARD", "DENY", "ROLE", EVERYONE));

        assertFalse(resolve(Set.of(EVERYONE, STAFF), serverRules, boardRules, "VIEW_BOARD").allowed(),
                "the board comes after the server, whatever the layer");
    }

    @Test
    void aPersonsRule_outweighsTheirRoles_andEveryone() {
        List<Permission> rules = List.of(
                server("VIEW_BOARD", "DENY", "ROLE", EVERYONE),
                server("VIEW_BOARD", "DENY", "ROLE", STAFF),
                server("VIEW_BOARD", "ALLOW", "USER", USER));

        assertTrue(resolve(Set.of(EVERYONE, STAFF), rules, List.of(), "VIEW_BOARD").allowed());
    }

    @Test
    void theTrace_showsEveryoneAsItsOwnLayer_beforeTheRoles() {
        List<Permission> rules = List.of(
                server("VIEW_BOARD", "DENY", "ROLE", EVERYONE),
                server("VIEW_BOARD", "ALLOW", "ROLE", STAFF));
        PermissionSnapshot snapshot = snapshot(Set.of(EVERYONE, STAFF), rules, List.of(), SERVER);

        List<PermissionResolver.Layer> layers = PermissionResolver.trace(snapshot, "VIEW_BOARD");

        assertEquals(List.of(PermissionResolver.TIER_EVERYONE, "ROLE"), layers.stream().map(PermissionResolver.Layer::tier).toList());
    }

    @Test
    void withoutKnowingWhichRoleIsEveryone_itIsAnOrdinaryRole() {
        List<Permission> rules = List.of(
                server("VIEW_BOARD", "DENY", "ROLE", EVERYONE),
                server("VIEW_BOARD", "ALLOW", "ROLE", STAFF));

        assertFalse(PermissionResolver.resolve(snapshot(Set.of(EVERYONE, STAFF), rules, List.of(), null), "VIEW_BOARD")
                .allowed(), "in one layer, the deny wins");
    }

    private static Decision resolve(Set<Long> roles, List<Permission> serverRules, List<Permission> boardRules,
                                    String key) {
        return PermissionResolver.resolve(snapshot(roles, serverRules, boardRules, EVERYONE), key);
    }

    private static PermissionSnapshot snapshot(Set<Long> roles, List<Permission> serverRules,
                                               List<Permission> boardRules, Long everyone) {
        return new PermissionSnapshot(USER, true, roles, Set.of(VIEW_CHANNEL), boardRules, serverRules, false, everyone);
    }

    private static long nextId = 1;

    private static Permission server(String key, String state, String subjectType, long subjectId) {
        return rule(key, state, "SERVER", SERVER, subjectType, subjectId);
    }

    private static Permission board(String key, String state, String subjectType, long subjectId) {
        return rule(key, state, "BOARD", BOARD, subjectType, subjectId);
    }

    private static Permission rule(String key, String state, String scopeType, long scopeId, String subjectType,
                                   long subjectId) {
        KanbanPermission kanbanPermission = new KanbanPermission();
        kanbanPermission.setKey(key);
        Permission permission = new Permission();
        permission.setId(nextId++);
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
