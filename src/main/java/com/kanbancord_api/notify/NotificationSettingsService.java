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
import java.util.HashMap;
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
     * {@code mentions} hold every event, on or off: whether it is posted, and whether the people
     * involved are mentioned when it is.
     */
    /**
     * {@code interactive}: its posts show the whole task, with buttons to change it.
     * {@code boardOverrides}: boards whose settings differ from the feed's, by board id.
     */
    public record Feed(Long feedId, Long channelId, List<Long> boardIds, Map<NotificationEvent, Boolean> events,
                       Map<NotificationEvent, Boolean> mentions, boolean mentionRoles, boolean interactive,
                       Map<Long, BoardOverride> boardOverrides) {

        public boolean covers(Long boardId) {
            return boardIds.isEmpty() || (boardId != null && boardIds.contains(boardId));
        }

        /** Whether the feed posts this event for this board: the board's own setting, or the feed's. */
        public boolean wants(Long boardId, NotificationEvent event) {
            BoardOverride own = boardOverrides.get(boardId);
            Boolean on = own != null && own.events().containsKey(event) ? own.events().get(event) : events.get(event);
            return Boolean.TRUE.equals(on);
        }

        /** Whether posting this event for this board mentions the people involved. */
        public boolean mentions(Long boardId, NotificationEvent event) {
            BoardOverride own = boardOverrides.get(boardId);
            Boolean on = own != null && own.mentions().containsKey(event) ? own.mentions().get(event) : mentions.get(event);
            return event.canMention() && Boolean.TRUE.equals(on);
        }

        Feed withOverrides(Map<Long, BoardOverride> overrides) {
            return new Feed(feedId, channelId, boardIds, events, mentions, mentionRoles, interactive, overrides);
        }
    }

    /** Where a board's settings for a feed differ from the feed's; everything left out follows the feed. */
    public record BoardOverride(Map<NotificationEvent, Boolean> events, Map<NotificationEvent, Boolean> mentions) {

        public int changes() {
            return events.size() + mentions.size();
        }
    }

    public List<Feed> feeds(Long serverId) {
        List<Feed> feeds = jdbcTemplate.query("""
                SELECT feed_id, channel_id, board_ids, events, mentions, mention_roles, interactive FROM notification_feeds
                WHERE server_id = ? ORDER BY feed_id
                """, feedMapper(), serverId);
        Map<Long, Map<Long, BoardOverride>> overrides = new HashMap<>();
        jdbcTemplate.query("""
                SELECT o.feed_id, o.board_id, o.events, o.mentions FROM feed_board_settings o
                JOIN notification_feeds f ON f.feed_id = o.feed_id WHERE f.server_id = ?
                """, rs -> {
            overrides.computeIfAbsent(rs.getLong("feed_id"), id -> new HashMap<>())
                    .put(rs.getLong("board_id"), new BoardOverride(eventFlags(readFlags(rs, "events")),
                            eventFlags(readFlags(rs, "mentions"))));
        }, serverId);
        return feeds.stream().map(feed -> feed.withOverrides(Map.copyOf(overrides.getOrDefault(feed.feedId(), Map.of()))))
                .toList();
    }

    /** Stored flags as events, skipping any this version does not know. */
    private static Map<NotificationEvent, Boolean> eventFlags(Map<String, Boolean> stored) {
        Map<NotificationEvent, Boolean> flags = new EnumMap<>(NotificationEvent.class);
        stored.forEach((key, on) -> tryParse(NotificationEvent.class, key).ifPresent(event -> flags.put(event, Boolean.TRUE.equals(on))));
        return flags;
    }

    /**
     * Changes a board's own settings for a feed. A null value goes back to following the feed, and so
     * does a value equal to the feed's: only real differences are kept. Returns the feed afterwards.
     */
    @Transactional
    public Feed saveBoardOverride(Long serverId, Long feedId, Long boardId, Map<String, Boolean> eventChanges,
                                  Map<String, Boolean> mentionChanges) {
        Feed feed = feeds(serverId).stream().filter(entry -> entry.feedId().equals(feedId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Feed", "feedId", feedId));
        if (!feed.covers(boardId)) {
            throw new BadRequestException("That feed does not post about this board");
        }
        BoardOverride current = feed.boardOverrides().getOrDefault(boardId, new BoardOverride(Map.of(), Map.of()));
        Map<NotificationEvent, Boolean> events = merged(current.events(), eventChanges, feed.events(), false);
        Map<NotificationEvent, Boolean> mentions = merged(current.mentions(), mentionChanges, feed.mentions(), true);
        if (events.isEmpty() && mentions.isEmpty()) {
            jdbcTemplate.update("DELETE FROM feed_board_settings WHERE feed_id = ? AND board_id = ?", feedId, boardId);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO feed_board_settings (feed_id, board_id, events, mentions)
                    VALUES (?, ?, CAST(? AS JSONB), CAST(? AS JSONB))
                    ON CONFLICT (feed_id, board_id) DO UPDATE SET events = EXCLUDED.events, mentions = EXCLUDED.mentions,
                        updated_at = CURRENT_TIMESTAMP
                    """, feedId, boardId, json(events), json(mentions));
        }
        return feeds(serverId).stream().filter(entry -> entry.feedId().equals(feedId)).findFirst().orElseThrow();
    }

    /** Board settings back to following the feed entirely. */
    public void clearBoardOverride(Long feedId, Long boardId) {
        jdbcTemplate.update("DELETE FROM feed_board_settings WHERE feed_id = ? AND board_id = ?", feedId, boardId);
    }

    private static Map<NotificationEvent, Boolean> merged(Map<NotificationEvent, Boolean> current,
                                                          Map<String, Boolean> changes,
                                                          Map<NotificationEvent, Boolean> feed, boolean mentions) {
        Map<NotificationEvent, Boolean> result = new EnumMap<>(NotificationEvent.class);
        result.putAll(current);
        if (changes != null) {
            changes.forEach((key, on) -> {
                NotificationEvent event = parse(NotificationEvent.class, key, "event");
                if (mentions && Boolean.TRUE.equals(on) && !event.canMention()) {
                    throw new BadRequestException(event.label() + " concerns no task, so there is nobody to mention");
                }
                if (on == null) {
                    result.remove(event);
                } else {
                    result.put(event, on);
                }
            });
        }
        result.entrySet().removeIf(entry -> entry.getValue().equals(Boolean.TRUE.equals(feed.get(entry.getKey()))));
        return result;
    }

    /** A new feed starts with each event's default, posting and mentioning, and is interactive. */
    public Feed defaultFeed(Long channelId, List<Long> boardIds) {
        Map<NotificationEvent, Boolean> events = new EnumMap<>(NotificationEvent.class);
        for (NotificationEvent event : NotificationEvent.values()) {
            events.put(event, event.feedDefault());
        }
        Map<NotificationEvent, Boolean> mentions = new EnumMap<>(NotificationEvent.class);
        for (NotificationEvent event : NotificationEvent.values()) {
            mentions.put(event, event.canMention() && event.mentionDefault());
        }
        return new Feed(null, channelId, boardIds, events, mentions, false, true, Map.of());
    }

    /**
     * Creates the feed, or replaces it when it has an id. Events and categories left out of the
     * request keep their current (or default) setting.
     */
    @Transactional
    public Feed saveFeed(Long serverId, Long feedId, Long channelId, List<Long> boardIds, Map<String, Boolean> events,
                         Map<String, Boolean> mentions, Boolean mentionRoles, Boolean interactive) {
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
        Map<NotificationEvent, Boolean> mentionFlags = new EnumMap<>(current.mentions());
        if (mentions != null) {
            applyMentions(mentionFlags, mentions, true);
        }
        boolean roles = mentionRoles != null ? mentionRoles : current.mentionRoles();
        boolean buttons = interactive != null ? interactive : current.interactive();

        Long id = feedId;
        if (id == null) {
            id = jdbcTemplate.queryForObject("""
                    INSERT INTO notification_feeds (server_id, channel_id, board_ids, events, mentions, mention_roles,
                        interactive)
                    VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?, ?) RETURNING feed_id
                    """, Long.class, serverId, channel, longArray(boards), json(eventFlags), json(mentionFlags), roles,
                    buttons);
        } else {
            Long existing = id;
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notification_feeds SET channel_id = ?, board_ids = ?, events = CAST(? AS JSONB),
                            mentions = CAST(? AS JSONB), mention_roles = ?, interactive = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE feed_id = ? AND server_id = ?
                        """);
                statement.setLong(1, channel);
                statement.setArray(2, connection.createArrayOf("bigint", boards.toArray()));
                statement.setString(3, json(eventFlags));
                statement.setString(4, json(mentionFlags));
                statement.setBoolean(5, roles);
                statement.setBoolean(6, buttons);
                statement.setLong(7, existing);
                statement.setLong(8, serverId);
                return statement;
            });
        }
        // Boards the feed no longer covers lose their own settings for it.
        if (!boards.isEmpty()) {
            jdbcTemplate.update("DELETE FROM feed_board_settings WHERE feed_id = ? AND NOT (board_id = ANY (?))",
                    id, longArray(boards));
        }
        return new Feed(id, channel, boards, eventFlags, mentionFlags, roles, buttons, Map.of());
    }

    public void deleteFeed(Long serverId, Long feedId) {
        int deleted = jdbcTemplate.update("DELETE FROM notification_feeds WHERE feed_id = ? AND server_id = ?", feedId, serverId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Feed", "feedId", feedId);
        }
    }

    /**
     * Applies mention switches keyed by event, or by category for all of its events. Categories go
     * first, so a request (or a feed saved before mentions were per event) can switch a category and
     * then single events in it. Unknown keys are refused in requests and skipped in stored feeds.
     */
    private static void applyMentions(Map<NotificationEvent, Boolean> flags, Map<String, Boolean> changes, boolean strict) {
        changes.forEach((key, on) -> tryParse(NotificationEvent.Category.class, key).ifPresent(category -> {
            for (NotificationEvent event : NotificationEvent.values()) {
                if (event.category() == category && event.canMention()) {
                    flags.put(event, Boolean.TRUE.equals(on));
                }
            }
        }));
        changes.forEach((key, on) -> {
            if (tryParse(NotificationEvent.Category.class, key).isPresent()) {
                return;
            }
            java.util.Optional<NotificationEvent> event = tryParse(NotificationEvent.class, key);
            if (event.isEmpty()) {
                if (strict) {
                    throw new BadRequestException("Unknown event or category: " + key);
                }
                return;
            }
            if (!event.get().canMention()) {
                if (strict && Boolean.TRUE.equals(on)) {
                    throw new BadRequestException(event.get().label() + " concerns no task, so there is nobody to mention");
                }
                return;
            }
            flags.put(event.get(), Boolean.TRUE.equals(on));
        });
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
            Map<NotificationEvent, Boolean> mentions = new EnumMap<>(defaults.mentions());
            applyMentions(mentions, readFlags(rs, "mentions"), false);
            Array boards = rs.getArray("board_ids");
            List<Long> boardIds = boards == null ? List.of() : Arrays.asList((Long[]) boards.getArray());
            return new Feed(rs.getLong("feed_id"), rs.getLong("channel_id"), boardIds, events, mentions,
                    rs.getBoolean("mention_roles"), rs.getBoolean("interactive"), Map.of());
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
    /**
     * {@code includeCommented}: also tasks this person commented on (off by default).
     * {@code includeFollowed}: also tasks they follow (on by default: following is asking to hear).
     */
    public record UserSettings(DmMode dmMode, Map<NotificationEvent, Boolean> events, boolean includeCommented,
                               boolean includeFollowed, Map<Long, ServerMode> servers) {

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
        return new UserSettings(DmMode.UNLESS_PINGED, events, false, true, Map.of());
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
        stored.put("includeFollowed", updated.includeFollowed());
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
        boolean includeFollowed = changes.get("includeFollowed") instanceof Boolean flag ? flag : base.includeFollowed();
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
        return new UserSettings(dmMode, events, includeCommented, includeFollowed, servers);
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
                    entry.put("canMention", event.canMention());
                    entry.put("mentionDefault", event.canMention() && event.mentionDefault());
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
