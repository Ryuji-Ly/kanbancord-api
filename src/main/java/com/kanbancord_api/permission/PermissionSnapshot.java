package com.kanbancord_api.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything needed to resolve any permission key for one user in one server (and optionally one
 * board), loaded up front so resolution itself needs no further queries.
 *
 * @param userId          the user being evaluated
 * @param member          whether the user is a member of the server; non-members get no role or
 *                        Discord-derived grants
 * @param roleIds         Discord roles the member holds
 * @param discordFlagBits Discord permission bits the member holds (server owner implies ADMINISTRATOR)
 * @param boardRules      rules scoped to the board (its overrides), or empty at server scope
 * @param serverRules     rules scoped to the server
 * @param openPermissions the server has open permissions on (see {@link PermissionResolver#OPEN_KEYS})
 */
public record PermissionSnapshot(
        Long userId,
        boolean member,
        Set<Long> roleIds,
        Set<Long> discordFlagBits,
        List<Permission> boardRules,
        List<Permission> serverRules,
        boolean openPermissions) {

    /** A snapshot of a server without open permissions. */
    public PermissionSnapshot(Long userId, boolean member, Set<Long> roleIds, Set<Long> discordFlagBits,
                              List<Permission> boardRules, List<Permission> serverRules) {
        this(userId, member, roleIds, discordFlagBits, boardRules, serverRules, false);
    }

    public PermissionSnapshot withBoardRules(List<Permission> rules) {
        return new PermissionSnapshot(userId, member, roleIds, discordFlagBits, rules, serverRules, openPermissions);
    }

    /**
     * Returns the snapshot as it would look after replacing {@code before} with {@code after}. Either may
     * be null (create / delete). Used to evaluate a rule change before persisting it.
     */
    public PermissionSnapshot withRuleChange(Permission before, Permission after) {
        List<Permission> server = without(serverRules, before);
        List<Permission> board = without(boardRules, before);
        if (after != null) {
            if ("BOARD".equals(after.getScopeType())) {
                board.add(after);
            } else {
                server.add(after);
            }
        }
        return new PermissionSnapshot(userId, member, roleIds, discordFlagBits, board, server, openPermissions);
    }

    private static List<Permission> without(List<Permission> rules, Permission removed) {
        List<Permission> copy = new ArrayList<>(rules);
        if (removed != null && removed.getId() != null) {
            copy.removeIf(rule -> Objects.equals(rule.getId(), removed.getId()));
        }
        return copy;
    }
}
