package com.kanbancord_api.permission;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * What a permission check needs to know about the person, in one query: whether they are a member,
 * their roles (with @everyone, whose id is the server's), the Discord permissions those roles give,
 * whether they own the server, and whether the server has open permissions on.
 */
@Component
public class MembershipQuery {

    /**
     * @param discordPermissions the permission bits of all their roles together
     * @param owner              whether they own the server; only ever true for a member
     */
    public record Membership(boolean member, Set<Long> roleIds, long discordPermissions, boolean owner,
                             boolean openPermissions) {

        static final Membership NONE = new Membership(false, Set.of(), 0L, false, false);
    }

    private final JdbcTemplate jdbcTemplate;

    public MembershipQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Membership load(Long serverId, Long userId) {
        // One row per role the member has (or one row without a role); none for an unknown server.
        boolean[] member = {false};
        boolean[] owner = {false};
        boolean[] open = {false};
        long[] permissions = {0L};
        Set<Long> roleIds = new HashSet<>();
        jdbcTemplate.query("""
                SELECT s.owner_id = ? AS owner, s.open_permissions, sm.id AS member_id, r.role_id, r.discord_permissions
                FROM servers s
                LEFT JOIN server_members sm ON sm.server_id = s.server_id AND sm.user_id = ?
                LEFT JOIN roles r ON sm.id IS NOT NULL AND r.server_id = s.server_id
                    AND (r.role_id = s.server_id OR EXISTS (
                        SELECT 1 FROM member_roles mr WHERE mr.server_member_id = sm.id AND mr.role_id = r.role_id))
                WHERE s.server_id = ?
                """, row -> {
            open[0] = row.getBoolean("open_permissions");
            if (row.getObject("member_id") == null) {
                return;
            }
            member[0] = true;
            owner[0] = row.getBoolean("owner");
            Long roleId = row.getObject("role_id", Long.class);
            if (roleId != null) {
                roleIds.add(roleId);
                permissions[0] |= row.getLong("discord_permissions");
            }
        }, userId, userId, serverId);
        return member[0] || open[0]
                ? new Membership(member[0], Set.copyOf(roleIds), permissions[0], owner[0], open[0])
                : Membership.NONE;
    }
}
