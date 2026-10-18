package com.kanbancord_api.sync;

import com.kanbancord_api.permission.PermissionBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Makes a server's roles and members exactly what the bot sent: everything is added or updated, and
 * roles and members that are no longer in the server are removed. The bot sends the complete lists
 * whenever it syncs a whole server (on joining it, on starting, and every few hours), so this is
 * also how changes missed while the API was unreachable are caught up.
 *
 * <p>Servers can have thousands of members, so everything is written in batches, in one
 * transaction: the whole sync takes a handful of statements per thousand members rather than
 * several per member. Rows that have not changed are left alone, not rewritten: most of a resync is
 * the same as last time, and in PostgreSQL every rewritten row leaves a dead copy to vacuum.
 */
@Service
public class ServerBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(ServerBootstrapService.class);
    private static final int BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final PermissionBootstrapService permissionBootstrapService;

    public ServerBootstrapService(JdbcTemplate jdbcTemplate, PermissionBootstrapService permissionBootstrapService) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionBootstrapService = permissionBootstrapService;
    }

    /** What changed, for the log. */
    public record Result(int roles, int members, int rolesRemoved, int membersRemoved) {
    }

    @Transactional
    public Result bootstrap(Long serverId, InternalBootstrapRequest request) {
        long started = System.currentTimeMillis();

        // Users first: the server's owner and every member must exist before anything refers to them.
        Map<Long, InternalBootstrapRequest.MemberEntry> members = new LinkedHashMap<>();
        for (InternalBootstrapRequest.MemberEntry entry : request.members()) {
            members.put(entry.userId(), entry);
        }
        List<Object[]> users = new ArrayList<>();
        if (!members.containsKey(request.ownerId())) {
            users.add(new Object[]{request.ownerId(), request.ownerUsername(), request.ownerGlobalName(),
                    request.ownerAvatarUrl()});
        }
        members.values().forEach(entry -> users.add(new Object[]{entry.userId(), entry.username(),
                entry.globalName(), entry.avatarUrl()}));
        batch("""
                INSERT INTO users (user_id, username, global_name, avatar_url) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, global_name = EXCLUDED.global_name,
                    avatar_url = EXCLUDED.avatar_url, updated_at = CURRENT_TIMESTAMP
                WHERE (users.username, users.global_name, users.avatar_url)
                    IS DISTINCT FROM (EXCLUDED.username, EXCLUDED.global_name, EXCLUDED.avatar_url)
                """, users);

        jdbcTemplate.update("""
                INSERT INTO servers (server_id, name, icon_url, owner_id, bot_present) VALUES (?, ?, ?, ?, TRUE)
                ON CONFLICT (server_id) DO UPDATE SET name = EXCLUDED.name, icon_url = EXCLUDED.icon_url,
                    owner_id = EXCLUDED.owner_id, bot_present = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE (servers.name, servers.icon_url, servers.owner_id, servers.bot_present)
                    IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.icon_url, EXCLUDED.owner_id, TRUE)
                """, serverId, request.name(), request.iconUrl(), request.ownerId());

        List<Object[]> roles = request.roles().stream()
                .map(role -> new Object[]{role.roleId(), serverId, role.name(), role.color(), role.position(),
                        role.discordPermissions()})
                .toList();
        batch("""
                INSERT INTO roles (role_id, server_id, name, color, position, discord_permissions) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (role_id) DO UPDATE SET server_id = EXCLUDED.server_id, name = EXCLUDED.name,
                    color = EXCLUDED.color, position = EXCLUDED.position,
                    discord_permissions = EXCLUDED.discord_permissions, updated_at = CURRENT_TIMESTAMP
                WHERE (roles.server_id, roles.name, roles.color, roles.position, roles.discord_permissions)
                    IS DISTINCT FROM (EXCLUDED.server_id, EXCLUDED.name, EXCLUDED.color, EXCLUDED.position,
                        EXCLUDED.discord_permissions)
                """, roles);

        List<Object[]> memberRows = members.values().stream()
                .map(entry -> {
                    Timestamp joined = entry.joinedAt() == null ? null : Timestamp.valueOf(entry.joinedAt());
                    return new Object[]{serverId, entry.userId(), entry.nickname(), joined, joined, joined};
                })
                .toList();
        batch("""
                INSERT INTO server_members (server_id, user_id, nickname, joined_at)
                VALUES (?, ?, ?, COALESCE(CAST(? AS TIMESTAMP), CURRENT_TIMESTAMP))
                ON CONFLICT (server_id, user_id) DO UPDATE SET nickname = EXCLUDED.nickname,
                    joined_at = COALESCE(CAST(? AS TIMESTAMP), server_members.joined_at), updated_at = CURRENT_TIMESTAMP
                WHERE server_members.nickname IS DISTINCT FROM EXCLUDED.nickname
                    OR server_members.joined_at IS DISTINCT FROM COALESCE(CAST(? AS TIMESTAMP), server_members.joined_at)
                """, memberRows);

        // Removals. An empty list can only mean the bot could not read it (a server always has its
        // owner and its @everyone role), so nothing is removed on its word.
        int membersRemoved = 0;
        if (!members.isEmpty()) {
            membersRemoved = deleteNotIn("DELETE FROM server_members WHERE server_id = ? AND NOT (user_id = ANY (?))",
                    serverId, members.keySet());
        }
        int rolesRemoved = 0;
        if (!request.roles().isEmpty()) {
            Set<Long> roleIds = request.roles().stream()
                    .map(InternalBootstrapRequest.RoleEntry::roleId)
                    .collect(Collectors.toSet());
            rolesRemoved = deleteNotIn("DELETE FROM roles WHERE server_id = ? AND NOT (role_id = ANY (?))", serverId, roleIds);
        }

        // Each member's roles, made what the bot sent by removing and adding only the differences;
        // skipped with the member list it depends on. Role ids the server does not have are skipped.
        if (!members.isEmpty()) {
            syncMemberRoles(serverId, members);
        }

        permissionBootstrapService.initializeDefaultServerConfiguration(serverId);

        Result result = new Result(roles.size(), members.size(), rolesRemoved, membersRemoved);
        log.info("Synced server {}: {} roles, {} members, removed {} roles and {} members, in {} ms", serverId,
                result.roles(), result.members(), rolesRemoved, membersRemoved, System.currentTimeMillis() - started);
        return result;
    }

    /**
     * Makes every member's roles what the bot sent, in two statements whatever the server's size: one
     * removes the roles members no longer have, one adds the ones they gained. A member sent without
     * a role list has none.
     */
    private void syncMemberRoles(Long serverId, Map<Long, InternalBootstrapRequest.MemberEntry> members) {
        List<Long> userIds = new ArrayList<>();
        List<Long> roleIds = new ArrayList<>();
        for (InternalBootstrapRequest.MemberEntry entry : members.values()) {
            if (entry.roleIds() == null) {
                continue;
            }
            for (Long roleId : Set.copyOf(entry.roleIds())) {
                userIds.add(entry.userId());
                roleIds.add(roleId);
            }
        }
        Long[] users = userIds.toArray(Long[]::new);
        Long[] roles = roleIds.toArray(Long[]::new);
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM member_roles mr
                    USING server_members sm
                    WHERE mr.server_member_id = sm.id AND sm.server_id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM unnest(?::bigint[], ?::bigint[]) AS wanted(user_id, role_id)
                          WHERE wanted.user_id = sm.user_id AND wanted.role_id = mr.role_id)
                    """);
            statement.setLong(1, serverId);
            statement.setArray(2, connection.createArrayOf("bigint", users));
            statement.setArray(3, connection.createArrayOf("bigint", roles));
            return statement;
        });
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO member_roles (server_member_id, role_id)
                    SELECT sm.id, r.role_id
                    FROM unnest(?::bigint[], ?::bigint[]) AS wanted(user_id, role_id)
                    JOIN server_members sm ON sm.server_id = ? AND sm.user_id = wanted.user_id
                    JOIN roles r ON r.role_id = wanted.role_id AND r.server_id = ?
                    ON CONFLICT DO NOTHING
                    """);
            statement.setArray(1, connection.createArrayOf("bigint", users));
            statement.setArray(2, connection.createArrayOf("bigint", roles));
            statement.setLong(3, serverId);
            statement.setLong(4, serverId);
            return statement;
        });
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            List<Object[]> chunk = rows.subList(from, Math.min(from + BATCH_SIZE, rows.size()));
            jdbcTemplate.batchUpdate(sql, chunk);
        }
    }

    private int deleteNotIn(String sql, Long serverId, Set<Long> keep) {
        return jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, serverId);
            statement.setArray(2, connection.createArrayOf("bigint", keep.toArray()));
            return statement;
        });
    }
}
