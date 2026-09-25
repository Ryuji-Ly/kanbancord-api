package com.kanbancord_api.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where a server's updates go (its audit channel and feeds), the server's channels as the bot last
 * reported them, and what each person wants by direct message.
 */
@Service
public class NotificationSettingsService {

    private static final TypeReference<Map<String, Boolean>> FLAGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public NotificationSettingsService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Channels ─────────────────────────────────────────────────────────────

    public record Channel(Long channelId, String name, String category, int position, boolean botCanPost) {
    }

    public List<Channel> channels(Long serverId) {
        return jdbcTemplate.query("""
                SELECT channel_id, name, category, position, bot_can_post FROM discord_channels
                WHERE server_id = ? ORDER BY position, name
                """, (rs, row) -> new Channel(rs.getLong("channel_id"), rs.getString("name"), rs.getString("category"),
                rs.getInt("position"), rs.getBoolean("bot_can_post")), serverId);
    }

    /** Makes the server's channels exactly those the bot reported. */
    @Transactional
    public void replaceChannels(Long serverId, List<Channel> channels) {
        jdbcTemplate.update("DELETE FROM discord_channels WHERE server_id = ?", serverId);
        jdbcTemplate.batchUpdate("""
                INSERT INTO discord_channels (channel_id, server_id, name, category, position, bot_can_post)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (channel_id) DO UPDATE SET server_id = EXCLUDED.server_id, name = EXCLUDED.name,
                    category = EXCLUDED.category, position = EXCLUDED.position, bot_can_post = EXCLUDED.bot_can_post
                """, channels.stream()
                .map(channel -> new Object[]{channel.channelId(), serverId, truncate(channel.name(), 100),
                        truncate(channel.category(), 100), channel.position(), channel.botCanPost()})
                .toList());
    }

    private void requireChannel(Long serverId, Long channelId) {
        Integer found = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM discord_channels WHERE server_id = ? AND channel_id = ?", Integer.class, serverId, channelId);
        if (found == null || found == 0) {
            throw new BadRequestException("That channel is not in this server, or the bot cannot see it");
        }
    }

    // ── Audit channel ────────────────────────────────────────────────────────

    public Optional<Long> auditChannel(Long serverId) {
        return jdbcTemplate.query("SELECT audit_channel_id FROM server_notification_settings WHERE server_id = ?",
                (rs, row) -> (Long) rs.getObject("audit_channel_id"), serverId).stream().filter(id -> id != null).findFirst();
    }

    public void setAuditChannel(Long serverId, Long channelId) {
        if (channelId != null) {
            requireChannel(serverId, channelId);
        }
        jdbcTemplate.update("""
                INSERT INTO server_notification_settings (server_id, audit_channel_id) VALUES (?, ?)
                ON CONFLICT (server_id) DO UPDATE SET audit_channel_id = EXCLUDED.audit_channel_id,
                    updated_at = CURRENT_TIMESTAMP
                """, serverId, channelId);
    }

    // ── Feeds ────────────────────────────────────────────────────────────────

    /**
     * An update feed. {@code boardIds} empty means every board in the server. {@code events} and
     * {@code mentions} hold every event and category, on or off.
     */
    public record Feed(Long feedId, Long channelId, List<Long> boardIds, Map<NotificationEvent, Boolean> events,
                       Map<NotificationEvent.Category, Boolean> mentions, boolean mentionRoles) {

        public boolean covers(Long boardId) {
            return boardIds.isEmpty() || (boardId != null && boardIds.contains(boardId));
        }

        public boolean wants(NotificationEvent event) {
            return Boolean.TRUE.equals(events.get(event));
        }

        public boolean mentions(NotificationEvent.Category category) {
            return Boolean.TRUE.equals(mentions.get(category));
        }
    }

    public List<Feed> feeds(Long serverId) {
        return jdbcTemplate.query("""
                SELECT feed_id, channel_id, board_ids, events, mentions, mention_roles FROM notification_feeds
                WHERE server_id = ? ORDER BY feed_id
                """, feedMapper(), serverId);
    }

    /** A new feed starts with each event's default and each category's default mention setting. */
    public Feed defaultFeed(Long channelId, List<Long> boardIds) {
        Map<NotificationEvent, Boolean> events = new EnumMap<>(NotificationEvent.class);
        for (NotificationEvent event : NotificationEvent.values()) {
            events.put(event, event.feedDefault());
        }
        Map<NotificationEvent.Category, Boolean> mentions = new EnumMap<>(NotificationEvent.Category.class);
        for (NotificationEvent.Category category : NotificationEvent.Category.values()) {
            mentions.put(category, category.mentionByDefault());
        }
        return new Feed(null, channelId, boardIds, events, mentions, false);
    }

    /**
     * Creates the feed, or replaces it when it has an id. Events and categories left out of the
     * request keep their current (or default) setting.
     */
    @Transactional
    public Feed saveFeed(Long serverId, Long feedId, Long channelId, List<Long> boardIds, Map<String, Boolean> events,
                         Map<String, Boolean> mentions, Boolean mentionRoles) {
        Feed current = feedId == null
                ? defaultFeed(channelId, List.of())
                : feeds(serverId).stream().filter(feed -> feed.feedId().equals(feedId)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Feed", "feedId", feedId));
        Long channel = channelId != null ? channelId : current.channelId();
        if (channel == null) {
            throw new BadRequestException("Pick a channel for the feed");
        }
        requireChannel(serverId, channel);
        List<Long> boards = boardIds != null ? List.copyOf(new HashSet<>(boardIds)) : current.boardIds();
        requireBoards(serverId, boards);

        Map<NotificationEvent, Boolean> eventFlags = new EnumMap<>(current.events());
        if (events != null) {
            events.forEach((key, on) -> eventFlags.put(parse(NotificationEvent.class, key, "event"), Boolean.TRUE.equals(on)));
        }
        Map<NotificationEvent.Category, Boolean> mentionFlags = new EnumMap<>(current.mentions());
        if (mentions != null) {
            mentions.forEach((key, on) ->
                    mentionFlags.put(parse(NotificationEvent.Category.class, key, "category"), Boolean.TRUE.equals(on)));
        }
        boolean roles = mentionRoles != null ? mentionRoles : current.mentionRoles();

        Long id = feedId;
        if (id == null) {
            id = jdbcTemplate.queryForObject("""
                    INSERT INTO notification_feeds (server_id, channel_id, board_ids, events, mentions, mention_roles)
                    VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?) RETURNING feed_id
                    """, Long.class, serverId, channel, longArray(boards), json(eventFlags), json(mentionFlags), roles);
        } else {
            Long existing = id;
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notification_feeds SET channel_id = ?, board_ids = ?, events = CAST(? AS JSONB),
                            mentions = CAST(? AS JSONB), mention_roles = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE feed_id = ? AND server_id = ?
                        """);
                statement.setLong(1, channel);
                statement.setArray(2, connection.createArrayOf("bigint", boards.toArray()));
                statement.setString(3, json(eventFlags));
                statement.setString(4, json(mentionFlags));
                statement.setBoolean(5, roles);
                statement.setLong(6, existing);
                statement.setLong(7, serverId);
                return statement;
            });
        }
        return new Feed(id, channel, boards, eventFlags, mentionFlags, roles);
    }

    public void deleteFeed(Long serverId, Long feedId) {
        int deleted = jdbcTemplate.update("DELETE FROM notification_feeds WHERE feed_id = ? AND server_id = ?", feedId, serverId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Feed", "feedId", feedId);
        }
    }

    private void requireBoards(Long serverId, List<Long> boardIds) {
        if (boardIds.isEmpty()) {
            return;
        }
        Integer found = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM boards WHERE server_id = ? AND board_id = ANY (?)", Integer.class,
                serverId, longArray(boardIds));
        if (found == null || found != boardIds.size()) {
            throw new BadRequestException("Every board of a feed must be in this server");
        }
    }

    private RowMapper<Feed> feedMapper() {
        return (rs, row) -> {
            Feed defaults = defaultFeed(null, List.of());
            Map<NotificationEvent, Boolean> events = new EnumMap<>(defaults.events());
            readFlags(rs, "events").forEach((key, on) -> tryParse(NotificationEvent.class, key)
                    .ifPresent(event -> events.put(event, on)));
            Map<NotificationEvent.Category, Boolean> mentions = new EnumMap<>(defaults.mentions());
            readFlags(rs, "mentions").forEach((key, on) -> tryParse(NotificationEvent.Category.class, key)
                    .ifPresent(category -> mentions.put(category, on)));
            Array boards = rs.getArray("board_ids");
            List<Long> boardIds = boards == null ? List.of() : Arrays.asList((Long[]) boards.getArray());
            return new Feed(rs.getLong("feed_id"), rs.getLong("channel_id"), boardIds, events, mentions,
                    rs.getBoolean("mention_roles"));
        };
    }

    // ── Personal settings ────────────────────────────────────────────────────

    public enum DmMode {
        /** Only if no feed mentioned the person for it in a channel they can see. */
        UNLESS_PINGED,
        ALWAYS,
        NEVER
    }

    public enum ServerMode {
        /** The person's own choices. */
        DEFAULT,
        /** Only being assigned or unassigned. */
        ASSIGNMENTS,
        NONE
    }

    /**
     * What a person wants by direct message: which events, about which tasks (those they are
     * assigned to or created, and optionally those they commented on), and per server a way to
     * narrow it down.
     */
    public record UserSettings(DmMode dmMode, Map<NotificationEvent, Boolean> events, boolean includeCommented,
                               Map<Long, ServerMode> servers) {

        public ServerMode serverMode(Long serverId) {
            return servers.getOrDefault(serverId, ServerMode.DEFAULT);
        }

        /** Whether this person wants to hear about {@code event} in this server. */
        public boolean wants(Long serverId, NotificationEvent event) {
            if (dmMode == DmMode.NEVER || !event.canDm()) {
                return false;
            }
            return switch (serverMode(serverId)) {
                case NONE -> false;
                case ASSIGNMENTS -> event.isAboutAssignee();
                case DEFAULT -> Boolean.TRUE.equals(events.get(event));
            };
        }
    }

    public static UserSettings defaultUserSettings() {
        Map<NotificationEvent, Boolean> events = new EnumMap<>(NotificationEvent.class);
        for (NotificationEvent event : NotificationEvent.values()) {
            if (event.canDm()) {
                events.put(event, event.dmDefault());
            }
        }
        return new UserSettings(DmMode.UNLESS_PINGED, events, false, Map.of());
    }

    public UserSettings userSettings(Long userId) {
        List<String> stored = jdbcTemplate.queryForList(
                "SELECT CAST(settings AS TEXT) FROM user_notification_settings WHERE user_id = ?", String.class, userId);
        UserSettings defaults = defaultUserSettings();
        if (stored.isEmpty()) {
            return defaults;
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(stored.get(0), new TypeReference<>() {
            });
            return merge(defaults, raw);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return defaults;
        }
    }

    /** Settings for many people at once, for working out who to notify. */
    public Map<Long, UserSettings> userSettings(Set<Long> userIds) {
        Map<Long, UserSettings> result = new LinkedHashMap<>();
        userIds.forEach(id -> result.put(id, userSettings(id)));
        return result;
    }

    /**
     * Changes a person's settings. Fields left out keep their current value; events and servers
     * left out keep theirs.
     */
    public UserSettings updateUserSettings(Long userId, Map<String, Object> changes) {
        UserSettings updated = merge(userSettings(userId), changes);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("dmMode", updated.dmMode().name());
        stored.put("events", enumKeys(updated.events()));
        stored.put("includeCommented", updated.includeCommented());
        Map<String, String> servers = new LinkedHashMap<>();
        updated.servers().forEach((id, mode) -> {
            if (mode != ServerMode.DEFAULT) {
                servers.put(String.valueOf(id), mode.name());
            }
        });
        stored.put("servers", servers);
        jdbcTemplate.update("""
                INSERT INTO user_notification_settings (user_id, settings) VALUES (?, CAST(? AS JSONB))
                ON CONFLICT (user_id) DO UPDATE SET settings = EXCLUDED.settings, updated_at = CURRENT_TIMESTAMP
                """, userId, writeJson(stored));
        return updated;
    }

    @SuppressWarnings("unchecked")
    private UserSettings merge(UserSettings base, Map<String, Object> changes) {
        DmMode dmMode = base.dmMode();
        if (changes.get("dmMode") instanceof String mode) {
            dmMode = parse(DmMode.class, mode, "direct message mode");
        }
        Map<NotificationEvent, Boolean> events = new EnumMap<>(base.events());
        if (changes.get("events") instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((key, on) -> {
                NotificationEvent event = parse(NotificationEvent.class, key, "event");
                if (!event.canDm()) {
                    throw new BadRequestException(event.label() + " is only announced in channels");
                }
                events.put(event, Boolean.TRUE.equals(on));
            });
        }
        boolean includeCommented = changes.get("includeCommented") instanceof Boolean flag ? flag : base.includeCommented();
        Map<Long, ServerMode> servers = new LinkedHashMap<>(base.servers());
        if (changes.get("servers") instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((key, mode) -> {
                Long serverId = AuditClassifier.longOf(key);
                if (serverId == null) {
                    throw new BadRequestException("Unknown server: " + key);
                }
                servers.put(serverId, mode == null ? ServerMode.DEFAULT : parse(ServerMode.class, String.valueOf(mode), "server setting"));
            });
        }
        return new UserSettings(dmMode, events, includeCommented, servers);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Boolean> readFlags(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        try {
            return text == null ? Map.of() : objectMapper.readValue(text, FLAGS);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static <E extends Enum<E>> Map<String, Boolean> enumKeys(Map<E, Boolean> flags) {
        Map<String, Boolean> json = new LinkedHashMap<>();
        flags.forEach((key, on) -> json.put(key.name(), on));
        return json;
    }

    private String json(Map<? extends Enum<?>, Boolean> flags) {
        Map<String, Boolean> json = new LinkedHashMap<>();
        flags.forEach((key, on) -> json.put(key.name(), on));
        return writeJson(json);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Array longArray(List<Long> values) {
        return jdbcTemplate.execute((java.sql.Connection connection) -> connection.createArrayOf("bigint", values.toArray()));
    }

    static <E extends Enum<E>> E parse(Class<E> type, String name, String what) {
        return tryParse(type, name).orElseThrow(() -> new BadRequestException("Unknown " + what + ": " + name));
    }

    static <E extends Enum<E>> Optional<E> tryParse(Class<E> type, String name) {
        try {
            return Optional.of(Enum.valueOf(type, name));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** Every event, grouped by category, for the settings pages. */
    public static List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> categories = new ArrayList<>();
        for (NotificationEvent.Category category : NotificationEvent.Category.values()) {
            List<Map<String, Object>> events = new ArrayList<>();
            for (NotificationEvent event : NotificationEvent.values()) {
                if (event.category() == category) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("key", event.name());
                    entry.put("label", event.label());
                    entry.put("feedDefault", event.feedDefault());
                    entry.put("canDm", event.canDm());
                    entry.put("dmDefault", event.dmDefault());
                    events.add(entry);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", category.name());
            entry.put("label", category.label());
            entry.put("mentionByDefault", category.mentionByDefault());
            entry.put("events", events);
            categories.add(entry);
        }
        return categories;
    }
}
