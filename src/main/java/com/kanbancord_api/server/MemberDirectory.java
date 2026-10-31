package com.kanbancord_api.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is a member of a server. With a bot token configured ("lazy" storage), only people who use KanbanCord in a
 * server, or are picked for something there, are stored; anyone else is looked up in Discord the first time they
 * are needed and stored from then on. Without a token every member is stored, as the bot's sync sends them.
 */
@Service
public class MemberDirectory {

    private static final Logger log = LoggerFactory.getLogger(MemberDirectory.class);

    /** How long Discord's "not a member" is believed, so someone outside a server cannot make us ask it repeatedly. */
    private static final Duration NOT_MEMBER_TTL = Duration.ofMinutes(5);
    /** Use is recorded at most this often per member: it only has to tell users from people who never were. */
    private static final Duration USE_INTERVAL = Duration.ofHours(6);
    private static final int SWEEP_THRESHOLD = 10_000;
    public static final int SEARCH_LIMIT = 25;

    private final JdbcTemplate jdbcTemplate;
    private final DiscordMemberClient discord;
    private final TransactionTemplate ownTransaction;
    private final Map<String, Instant> notMembers = new ConcurrentHashMap<>();
    private final Map<String, Instant> usedAt = new ConcurrentHashMap<>();

    public MemberDirectory(JdbcTemplate jdbcTemplate, DiscordMemberClient discord,
                           PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.discord = discord;
        // A member found in Discord is saved on its own, even when the check runs inside a read-only transaction.
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Whether members are stored only when needed. */
    public boolean lazy() {
        return discord.configured();
    }

    public boolean isStored(Long serverId, Long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM server_members WHERE server_id = ? AND user_id = ?)",
                Boolean.class, serverId, userId));
    }

    /**
     * Whether the person is a member of the server: stored, or (with lazy storage) found in Discord, in which case
     * they are stored with their roles. Only servers the bot is in are looked up.
     *
     * @throws ResponseStatusException 503 when Discord could not be asked, rather than wrongly answering no
     */
    public boolean ensure(Long serverId, Long userId) {
        if (isStored(serverId, userId)) {
            return true;
        }
        if (!lazy() || userId == null) {
            return false;
        }
        String key = serverId + ":" + userId;
        Instant now = Instant.now();
        Instant refused = notMembers.get(key);
        if (refused != null && refused.plus(NOT_MEMBER_TTL).isAfter(now)) {
            return false;
        }
        Boolean present = jdbcTemplate.query("SELECT bot_present FROM servers WHERE server_id = ?",
                row -> row.next() ? row.getBoolean(1) : Boolean.FALSE, serverId);
        if (!Boolean.TRUE.equals(present)) {
            return false;
        }
        Optional<DiscordMemberClient.DiscordMember> found;
        try {
            found = discord.fetch(serverId, userId);
        } catch (DiscordMemberClient.UnavailableException ex) {
            log.warn("{}: {}", ex.getMessage(), ex.getCause() == null ? "" : ex.getCause().getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Discord is busy, try again shortly");
        }
        if (found.isEmpty()) {
            if (notMembers.size() > SWEEP_THRESHOLD) {
                notMembers.values().removeIf(at -> at.plus(NOT_MEMBER_TTL).isBefore(now));
            }
            notMembers.put(key, now);
            return false;
        }
        DiscordMemberClient.DiscordMember member = found.get();
        ownTransaction.executeWithoutResult(status -> store(serverId, member));
        return true;
    }

    /** Stores a member Discord gave, with the roles of theirs that the server has. */
    void store(Long serverId, DiscordMemberClient.DiscordMember member) {
        jdbcTemplate.update("""
                INSERT INTO users (user_id, username, global_name, avatar_url) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, global_name = EXCLUDED.global_name,
                    avatar_url = EXCLUDED.avatar_url, updated_at = CURRENT_TIMESTAMP
                WHERE (users.username, users.global_name, users.avatar_url)
                    IS DISTINCT FROM (EXCLUDED.username, EXCLUDED.global_name, EXCLUDED.avatar_url)
                """, member.userId(), member.username(), member.globalName(), member.avatarUrl());
        Timestamp joined = member.joinedAt() == null ? null : Timestamp.valueOf(member.joinedAt());
        jdbcTemplate.update("""
                INSERT INTO server_members (server_id, user_id, nickname, joined_at)
                VALUES (?, ?, ?, COALESCE(CAST(? AS TIMESTAMP), CURRENT_TIMESTAMP))
                ON CONFLICT (server_id, user_id) DO NOTHING
                """, serverId, member.userId(), member.nickname(), joined);
        Long[] roles = member.roleIds().toArray(Long[]::new);
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO member_roles (server_member_id, role_id)
                    SELECT sm.id, r.role_id
                    FROM server_members sm
                    JOIN roles r ON r.server_id = sm.server_id AND r.role_id = ANY (?)
                    WHERE sm.server_id = ? AND sm.user_id = ?
                    ON CONFLICT DO NOTHING
                    """);
            statement.setArray(1, connection.createArrayOf("bigint", roles));
            statement.setLong(2, serverId);
            statement.setLong(3, member.userId());
            return statement;
        });
        notMembers.remove(serverId + ":" + member.userId());
    }

    /**
     * Records that the person used KanbanCord in the server, which keeps them stored. Written at most every few
     * hours per member.
     */
    public void markUsed(Long serverId, Long userId) {
        String key = serverId + ":" + userId;
        Instant now = Instant.now();
        Instant last = usedAt.get(key);
        if (last != null && last.plus(USE_INTERVAL).isAfter(now)) {
            return;
        }
        if (usedAt.size() > SWEEP_THRESHOLD) {
            usedAt.values().removeIf(at -> at.plus(USE_INTERVAL).isBefore(now));
        }
        usedAt.put(key, now);
        try {
            // Its own transaction: the check may run inside a read-only one.
            ownTransaction.executeWithoutResult(status -> jdbcTemplate.update("""
                    UPDATE server_members SET last_used_at = now()
                    WHERE server_id = ? AND user_id = ?
                      AND (last_used_at IS NULL OR last_used_at < now() - make_interval(hours => ?))
                    """, serverId, userId, (int) USE_INTERVAL.toHours()));
        } catch (RuntimeException ex) {
            // Only bookkeeping: never fail the request over it.
            usedAt.remove(key);
            log.warn("Could not record use of server {} by {}: {}", serverId, userId, ex.getMessage());
        }
    }

    /**
     * Members matching {@code query} by name: stored ones first, then (with lazy storage) the ones Discord's member
     * search finds, which are not stored until someone picks them. An empty query lists stored members.
     */
    @Transactional(readOnly = true)
    public List<ServerMemberResponse> search(Long serverId, String query) {
        String text = query == null ? "" : query.trim();
        Map<Long, ServerMemberResponse> found = new LinkedHashMap<>();
        String like = "%" + text.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        jdbcTemplate.query("""
                SELECT sm.id, sm.user_id, sm.nickname, sm.joined_at, u.username, u.global_name, u.avatar_url
                FROM server_members sm JOIN users u ON u.user_id = sm.user_id
                WHERE sm.server_id = ?
                  AND (lower(u.username) LIKE ? OR lower(coalesce(u.global_name, '')) LIKE ?
                       OR lower(coalesce(sm.nickname, '')) LIKE ?)
                ORDER BY lower(coalesce(sm.nickname, u.global_name, u.username))
                LIMIT ?
                """, row -> {
            Long userId = row.getLong("user_id");
            String globalName = row.getString("global_name");
            String username = row.getString("username");
            Timestamp joined = row.getTimestamp("joined_at");
            found.put(userId, new ServerMemberResponse(row.getLong("id"), serverId, userId, row.getString("nickname"),
                    globalName != null ? globalName : username, username, row.getString("avatar_url"),
                    joined == null ? null : joined.toLocalDateTime()));
        }, serverId, like, like, like, SEARCH_LIMIT);

        if (lazy() && !text.isEmpty() && found.size() < SEARCH_LIMIT) {
            try {
                for (DiscordMemberClient.DiscordMember member : discord.search(serverId, text, SEARCH_LIMIT)) {
                    found.putIfAbsent(member.userId(), new ServerMemberResponse(null, serverId, member.userId(),
                            member.nickname(), member.globalName() != null ? member.globalName() : member.username(),
                            member.username(), member.avatarUrl(), member.joinedAt()));
                }
            } catch (DiscordMemberClient.UnavailableException ex) {
                // The stored matches still help; Discord's are added once it answers again.
                log.warn("{}: {}", ex.getMessage(), ex.getCause() == null ? "" : ex.getCause().getMessage());
            }
        }
        return new ArrayList<>(found.values()).subList(0, Math.min(found.size(), SEARCH_LIMIT));
    }
}
