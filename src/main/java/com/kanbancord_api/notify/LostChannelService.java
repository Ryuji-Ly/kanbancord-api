package com.kanbancord_api.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notices what stopped working when the bot reports a server's channels: a channel it could post in
 * (or make threads in) and no longer can, or one that was deleted, while a feed, the audit log, task
 * threads or a board post use it. The bot tells the server's managers. Only changes are reported, so
 * each problem is told once, when it starts.
 */
@Service
public class LostChannelService {

    private final JdbcTemplate jdbcTemplate;
    private final NotificationSettingsService settings;

    public LostChannelService(JdbcTemplate jdbcTemplate, NotificationSettingsService settings) {
        this.jdbcTemplate = jdbcTemplate;
        this.settings = settings;
    }

    /**
     * A channel where something stopped working. {@code deleted}: the channel is gone. {@code posting}:
     * the bot can no longer post there; {@code threads}: it can no longer make the kind of thread a board
     * there needs. What used it: the boards of each feed ("every board" is an empty list), the audit
     * log, the boards with task threads there, and the boards with a post there.
     */
    public record LostChannel(String channelId, String name, boolean deleted, boolean posting, boolean threads,
                              List<List<String>> feeds, boolean audit, List<String> threadBoards,
                              List<String> postBoards) {

        boolean isEmpty() {
            return feeds.isEmpty() && !audit && threadBoards.isEmpty() && postBoards.isEmpty();
        }
    }

    private record Was(String name, boolean post, boolean thread, boolean privateThread) {
    }

    /** Replaces the server's channels, and returns what stopped working because of the change. */
    @Transactional
    public List<LostChannel> replaceChannels(Long serverId, List<NotificationSettingsService.Channel> channels) {
        Map<Long, Was> before = new HashMap<>();
        jdbcTemplate.query("""
                SELECT channel_id, name, bot_can_post, bot_can_thread, bot_can_private_thread
                FROM discord_channels WHERE server_id = ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> before.put(rs.getLong(1),
                new Was(rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5))), serverId);
        settings.replaceChannels(serverId, channels);
        // A post the bot can reach again may be told about again if it is lost again later.
        for (NotificationSettingsService.Channel channel : channels) {
            if (channel.botCanPost()) {
                jdbcTemplate.update("UPDATE board_posts SET blocked_notice_at = NULL WHERE server_id = ? AND channel_id = ?",
                        serverId, channel.channelId());
            }
        }

        Map<Long, NotificationSettingsService.Channel> now = new HashMap<>();
        channels.forEach(channel -> now.put(channel.channelId(), channel));
        List<LostChannel> lost = new ArrayList<>();
        before.forEach((channelId, was) -> {
            NotificationSettingsService.Channel is = now.get(channelId);
            boolean deleted = is == null;
            boolean posting = was.post() && (deleted || !is.botCanPost());
            boolean publicThreads = was.thread() && (deleted || !is.botCanThread());
            boolean privateThreads = was.privateThread() && (deleted || !is.botCanPrivateThread());
            if (!posting && !publicThreads && !privateThreads) {
                return;
            }
            LostChannel found = uses(serverId, channelId, deleted ? was.name() : is.name(), deleted, posting,
                    publicThreads, privateThreads);
            if (!found.isEmpty()) {
                lost.add(found);
            }
        });
        return lost;
    }

    private LostChannel uses(Long serverId, Long channelId, String name, boolean deleted, boolean posting,
                             boolean publicThreads, boolean privateThreads) {
        List<List<String>> feeds = posting
                ? jdbcTemplate.query("SELECT board_ids FROM notification_feeds WHERE server_id = ? AND channel_id = ? ORDER BY feed_id",
                        (rs, row) -> boardNames(serverId, (Long[]) rs.getArray(1).getArray()), serverId, channelId)
                : List.of();
        boolean audit = posting && settings.auditChannel(serverId).filter(channelId::equals).isPresent();
        List<String> threadBoards = jdbcTemplate.queryForList("""
                SELECT b.name FROM board_thread_settings t JOIN boards b ON b.board_id = t.board_id
                WHERE b.server_id = ? AND t.channel_id = ?
                  AND ((t.private_threads AND ?) OR (NOT t.private_threads AND ?))
                ORDER BY b.name
                """, String.class, serverId, channelId, privateThreads, publicThreads);
        // Posts told about here are not told about again when they fail to update.
        List<String> postBoards = posting
                ? jdbcTemplate.queryForList("""
                        UPDATE board_posts p SET blocked_notice_at = now()
                        FROM boards b
                        WHERE b.board_id = p.board_id AND p.server_id = ? AND p.channel_id = ? AND p.blocked_notice_at IS NULL
                        RETURNING b.name
                        """, String.class, serverId, channelId)
                : List.of();
        return new LostChannel(String.valueOf(channelId), name, deleted, posting, publicThreads || privateThreads,
                feeds, audit, threadBoards, postBoards.stream().distinct().sorted().toList());
    }

    private List<String> boardNames(Long serverId, Long[] boardIds) {
        if (boardIds.length == 0) {
            return List.of();
        }
        return jdbcTemplate.queryForList("SELECT name FROM boards WHERE server_id = ? AND board_id = ANY (?) ORDER BY name",
                String.class, serverId, boardIds);
    }
}
