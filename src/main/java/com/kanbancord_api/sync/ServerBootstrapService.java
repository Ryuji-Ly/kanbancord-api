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
 * several per member.
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
        for (InternalBootstrapRequest.MemberEntry entry : request.getMembers()) {
            members.put(entry.getUserId(), entry);
        }
        List<Object[]> users = new ArrayList<>();
        if (!members.containsKey(request.getOwnerId())) {
            users.add(new Object[]{request.getOwnerId(), request.getOwnerUsername(), request.getOwnerGlobalName(),
                    request.getOwnerAvatarUrl()});
        }
        members.values().forEach(entry -> users.add(new Object[]{entry.getUserId(), entry.getUsername(),
                entry.getGlobalName(), entry.getAvatarUrl()}));
        batch("""
                INSERT INTO users (user_id, username, global_name, avatar_url) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, global_name = EXCLUDED.global_name,
                    avatar_url = EXCLUDED.avatar_url, updated_at = CURRENT_TIMESTAMP
                """, users);

        jdbcTemplate.update("""
                INSERT INTO servers (server_id, name, icon_url, owner_id, bot_present) VALUES (?, ?, ?, ?, TRUE)
                ON CONFLICT (server_id) DO UPDATE SET name = EXCLUDED.name, icon_url = EXCLUDED.icon_url,
                    owner_id = EXCLUDED.owner_id, bot_present = TRUE, updated_at = CURRENT_TIMESTAMP
                """, serverId, request.getName(), request.getIconUrl(), request.getOwnerId());

        List<Object[]> roles = request.getRoles().stream()
                .map(role -> new Object[]{role.getRoleId(), serverId, role.getName(), role.getColor(), role.getPosition(),
                        role.getDiscordPermissions()})
                .toList();
        batch("""
                INSERT INTO roles (role_id, server_id, name, color, position, discord_permissions) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (role_id) DO UPDATE SET server_id = EXCLUDED.server_id, name = EXCLUDED.name,
                    color = EXCLUDED.color, position = EXCLUDED.position,
                    discord_permissions = EXCLUDED.discord_permissions, updated_at = CURRENT_TIMESTAMP
                """, roles);

        List<Object[]> memberRows = members.values().stream()
                .map(entry -> {
                    Timestamp joined = entry.getJoinedAt() == null ? null : Timestamp.valueOf(entry.getJoinedAt());
                    return new Object[]{serverId, entry.getUserId(), entry.getNickname(), joined, joined};
                })
                .toList();
        batch("""
                INSERT INTO server_members (server_id, user_id, nickname, joined_at)
                VALUES (?, ?, ?, COALESCE(CAST(? AS TIMESTAMP), CURRENT_TIMESTAMP))
                ON CONFLICT (server_id, user_id) DO UPDATE SET nickname = EXCLUDED.nickname,
                    joined_at = COALESCE(CAST(? AS TIMESTAMP), server_members.joined_at), updated_at = CURRENT_TIMESTAMP
                """, memberRows);

        // Removals. An empty list can only mean the bot could not read it (a server always has its
        // owner and its @everyone role), so nothing is removed on its word.
        int membersRemoved = 0;
        if (!members.isEmpty()) {
            membersRemoved = deleteNotIn("DELETE FROM server_members WHERE server_id = ? AND NOT (user_id = ANY (?))",
                    serverId, members.keySet());
        }
        int rolesRemoved = 0;
        if (!request.getRoles().isEmpty()) {
            Set<Long> roleIds = request.getRoles().stream()
                    .map(InternalBootstrapRequest.RoleEntry::getRoleId)
                    .collect(Collectors.toSet());
            rolesRemoved = deleteNotIn("DELETE FROM roles WHERE server_id = ? AND NOT (role_id = ANY (?))", serverId, roleIds);
        }

        // Each member's roles, replaced wholesale; skipped with the member list it depends on. Role ids
        // the server does not have are skipped.
        if (!members.isEmpty()) {
            replaceMemberRoles(serverId, members);
        }

        permissionBootstrapService.initializeDefaultServerConfiguration(serverId);

        Result result = new Result(roles.size(), members.size(), rolesRemoved, membersRemoved);
        log.info("Synced server {}: {} roles, {} members, removed {} roles and {} members, in {} ms", serverId,
                result.roles(), result.members(), rolesRemoved, membersRemoved, System.currentTimeMillis() - started);
        return result;
    }

    private void replaceMemberRoles(Long serverId, Map<Long, InternalBootstrapRequest.MemberEntry> members) {
        jdbcTemplate.update("""
                DELETE FROM member_roles
                WHERE server_member_id IN (SELECT id FROM server_members WHERE server_id = ?)
                """, serverId);
        List<Object[]> memberRoles = new ArrayList<>();
        for (InternalBootstrapRequest.MemberEntry entry : members.values()) {
            if (entry.getRoleIds() == null) {
                continue;
            }
            for (Long roleId : Set.copyOf(entry.getRoleIds())) {
                memberRoles.add(new Object[]{roleId, serverId, serverId, entry.getUserId()});
            }
        }
        batch("""
                INSERT INTO member_roles (server_member_id, role_id)
                SELECT sm.id, r.role_id FROM server_members sm JOIN roles r ON r.role_id = ? AND r.server_id = ?
                WHERE sm.server_id = ? AND sm.user_id = ?
                ON CONFLICT DO NOTHING
                """, memberRoles);
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
