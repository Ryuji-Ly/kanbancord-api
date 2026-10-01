package com.kanbancord_api.permission;

import com.kanbancord_api.server.ServerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who among many people may see a board: the same rules as {@link PermissionEvaluationService}, but
 * for thousands of people at once, with a fixed handful of queries instead of several per person.
 * Used before posting a board in a Discord channel, to warn when the channel's audience is wider
 * than the board's.
 */
@Service
@Transactional(readOnly = true)
public class BoardAudienceQuery {

    /** Seeing a board post means seeing the board and its tasks. */
    public static final List<String> SEES_BOARD_POST = List.of("VIEW_BOARD", "VIEW_TASK");

    private final JdbcTemplate jdbcTemplate;
    private final PermissionEvaluationService permissionEvaluationService;
    private final ServerRepository serverRepository;
    private final DiscordPermissionParser discordPermissionParser;

    public BoardAudienceQuery(JdbcTemplate jdbcTemplate, PermissionEvaluationService permissionEvaluationService,
                              ServerRepository serverRepository, DiscordPermissionParser discordPermissionParser) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionEvaluationService = permissionEvaluationService;
        this.serverRepository = serverRepository;
        this.discordPermissionParser = discordPermissionParser;
    }

    /** Those of {@code userIds} who are not allowed every one of {@code keys} on the board, in the order given. */
    public List<Long> lacking(Long serverId, Long boardId, Collection<Long> userIds, Collection<String> keys) {
        Set<Long> wanted = new LinkedHashSet<>(userIds);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<Permission> serverRules = permissionEvaluationService.serverRules(serverId);
        List<Permission> boardRules = permissionEvaluationService.boardRules(serverId, boardId);
        Long ownerId = serverRepository.findOwnerIdByServerId(serverId).orElse(null);
        boolean open = serverRepository.findOpenPermissions(serverId).orElse(false);

        // @everyone has the server's id, and applies to every member without being assigned.
        Map<Long, Long> everyone = new HashMap<>();
        jdbcTemplate.query("SELECT role_id, discord_permissions FROM roles WHERE role_id = ? AND server_id = ?",
                row -> {
                    everyone.put(row.getLong(1), row.getLong(2));
                }, serverId, serverId);

        Set<Long> members = new HashSet<>();
        Map<Long, Set<Long>> rolesOf = new HashMap<>();
        Map<Long, Long> bitsOf = new HashMap<>();
        Array ids = jdbcTemplate.execute((java.sql.Connection connection) ->
                connection.createArrayOf("bigint", wanted.toArray()));
        jdbcTemplate.query("""
                SELECT sm.user_id, r.role_id, r.discord_permissions
                FROM server_members sm
                LEFT JOIN member_roles mr ON mr.server_member_id = sm.id
                LEFT JOIN roles r ON r.role_id = mr.role_id AND r.server_id = sm.server_id
                WHERE sm.server_id = ? AND sm.user_id = ANY (?)
                """, row -> {
            long userId = row.getLong(1);
            members.add(userId);
            long roleId = row.getLong(2);
            if (!row.wasNull()) {
                rolesOf.computeIfAbsent(userId, id -> new HashSet<>()).add(roleId);
                bitsOf.merge(userId, row.getLong(3), (a, b) -> a | b);
            }
        }, serverId, ids);

        List<Long> lacking = new ArrayList<>();
        for (Long userId : wanted) {
            PermissionSnapshot snapshot = snapshotOf(userId, members.contains(userId), rolesOf, bitsOf, everyone,
                    ownerId, boardRules, serverRules, open);
            boolean allowed = keys.stream().allMatch(key -> PermissionResolver.resolve(snapshot, key).allowed());
            if (!allowed) {
                lacking.add(userId);
            }
        }
        return lacking;
    }

    /** The same snapshot {@link PermissionEvaluationService#loadSnapshot} would load for this person. */
    private PermissionSnapshot snapshotOf(Long userId, boolean member, Map<Long, Set<Long>> rolesOf,
                                          Map<Long, Long> bitsOf, Map<Long, Long> everyone, Long ownerId,
                                          List<Permission> boardRules, List<Permission> serverRules, boolean open) {
        if (!member) {
            return new PermissionSnapshot(userId, false, Set.of(), Set.of(), boardRules, serverRules, open);
        }
        Set<Long> roleIds = new HashSet<>(rolesOf.getOrDefault(userId, Set.of()));
        long bits = bitsOf.getOrDefault(userId, 0L);
        for (Map.Entry<Long, Long> role : everyone.entrySet()) {
            roleIds.add(role.getKey());
            bits |= role.getValue();
        }
        Set<DiscordPermissionFlag> flags = EnumSet.noneOf(DiscordPermissionFlag.class);
        flags.addAll(discordPermissionParser.parse(bits));
        if (userId.equals(ownerId)) {
            flags.add(DiscordPermissionFlag.ADMINISTRATOR);
        }
        Set<Long> flagBits = new HashSet<>();
        for (DiscordPermissionFlag flag : flags) {
            flagBits.add(flag.getBit());
        }
        return new PermissionSnapshot(userId, true, Set.copyOf(roleIds), Set.copyOf(flagBits), boardRules, serverRules, open);
    }
}
