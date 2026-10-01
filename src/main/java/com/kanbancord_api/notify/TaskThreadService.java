package com.kanbancord_api.notify;

import com.kanbancord_api.exception.BadRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A thread per task: switched on per board, in one of the channels with a feed covering the board. The
 * bot makes each task's thread when something first happens to the task, and reports it here.
 */
@Service
public class TaskThreadService {

    /** Where a task's updates go once it has a thread. */
    public enum Updates { BOTH, THREAD, CHANNEL }

    public record Settings(Long boardId, Long channelId, boolean privateThreads, Updates updates) {
    }

    public record TaskThread(Long taskId, Long channelId, Long threadId, boolean privateThread) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final NotificationSettingsService notificationSettings;

    public TaskThreadService(JdbcTemplate jdbcTemplate, NotificationSettingsService notificationSettings) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationSettings = notificationSettings;
    }

    public Optional<Settings> settings(Long boardId) {
        return jdbcTemplate.query("""
                SELECT board_id, channel_id, private_threads, updates FROM board_thread_settings WHERE board_id = ?
                """, (rs, row) -> new Settings(rs.getLong("board_id"), rs.getLong("channel_id"),
                rs.getBoolean("private_threads"), Updates.valueOf(rs.getString("updates"))), boardId)
                .stream().findFirst();
    }

    /** The board's settings while they work: on, and a feed covering the board still posts in that channel. */
    public Optional<Settings> active(Long serverId, Long boardId) {
        return settings(boardId).filter(settings -> feedChannels(serverId, boardId).contains(settings.channelId()));
    }

    /** The channels with a feed covering the board: where its threads can go. */
    public List<Long> feedChannels(Long serverId, Long boardId) {
        return notificationSettings.feeds(serverId).stream()
                .filter(feed -> feed.covers(boardId))
                .map(NotificationSettingsService.Feed::channelId)
                .distinct()
                .toList();
    }

    /**
     * Switches threads on for the board, or changes how. The channel must have a feed covering the
     * board, and the bot must be able to make that kind of thread there.
     */
    @Transactional
    public Settings save(Long serverId, Long boardId, Long channelId, boolean privateThreads, String updates) {
        if (channelId == null) {
            throw new BadRequestException("Choose the feed channel the threads go in");
        }
        if (!feedChannels(serverId, boardId).contains(channelId)) {
            throw new BadRequestException("Threads go in a channel with an update feed for this board. Add a feed there "
                    + "first, or choose one of the board's feed channels.");
        }
        NotificationSettingsService.Channel channel = notificationSettings.channels(serverId).stream()
                .filter(entry -> entry.channelId().equals(channelId)).findFirst()
                .orElseThrow(() -> new BadRequestException("That channel is not one the bot knows about"));
        if (privateThreads && !channel.botCanPrivateThread()) {
            throw new BadRequestException("The bot cannot make private threads in #" + channel.name() + ". Private "
                    + "threads need a text channel (not an announcement channel) where the bot may create private "
                    + "threads and send messages in threads.");
        }
        if (!privateThreads && !channel.botCanThread()) {
            throw new BadRequestException("The bot cannot make threads in #" + channel.name() + ". Give it Create "
                    + "Public Threads and Send Messages in Threads there.");
        }
        Updates mode = parse(updates);
        jdbcTemplate.update("""
                INSERT INTO board_thread_settings (board_id, channel_id, private_threads, updates, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (board_id) DO UPDATE SET channel_id = EXCLUDED.channel_id,
                    private_threads = EXCLUDED.private_threads, updates = EXCLUDED.updates, updated_at = now()
                """, boardId, channelId, privateThreads, mode.name());
        return new Settings(boardId, channelId, privateThreads, mode);
    }

    public void clear(Long boardId) {
        jdbcTemplate.update("DELETE FROM board_thread_settings WHERE board_id = ?", boardId);
    }

    public Optional<TaskThread> thread(Long taskId) {
        return jdbcTemplate.query("""
                SELECT task_id, channel_id, thread_id, private_thread FROM task_threads WHERE task_id = ?
                """, (rs, row) -> new TaskThread(rs.getLong("task_id"), rs.getLong("channel_id"), rs.getLong("thread_id"),
                rs.getBoolean("private_thread")), taskId).stream().findFirst();
    }

    /** The thread the bot made for a task; a later one replaces it. */
    public void recordThread(Long serverId, Long taskId, Long channelId, Long threadId, boolean privateThread) {
        jdbcTemplate.update("""
                INSERT INTO task_threads (task_id, server_id, channel_id, thread_id, private_thread)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (task_id) DO UPDATE SET server_id = EXCLUDED.server_id, channel_id = EXCLUDED.channel_id,
                    thread_id = EXCLUDED.thread_id, private_thread = EXCLUDED.private_thread, created_at = now()
                """, taskId, serverId, channelId, threadId, privateThread);
    }

    /** The thread is gone (deleted in Discord): the task gets a new one next time. */
    public void forgetThread(Long taskId, Long threadId) {
        jdbcTemplate.update("DELETE FROM task_threads WHERE task_id = ? AND thread_id = ?", taskId, threadId);
    }

    private static Updates parse(String updates) {
        if (updates == null || updates.isBlank()) {
            return Updates.BOTH;
        }
        try {
            return Updates.valueOf(updates.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Updates go to BOTH, THREAD or CHANNEL");
        }
    }
}
