package com.kanbancord_api.boardpost;

import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.exception.BadRequestException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Array;
import java.util.List;

/**
 * Board posts: messages in Discord showing a whole board, kept up to date by the bot. Any change to
 * a board marks its posts out of date, in the same transaction as the change; the bot claims out of
 * date posts, redraws them and reports back.
 *
 * <p>A post is redrawn at most every couple of seconds however busy its board is: it is only claimed
 * once it has been out of date for {@link #SETTLE_SECONDS}, so a burst of changes becomes one edit.
 */
@Service
public class BoardPostService {

    /** Posts per board and per server, so a server cannot flood the bot with edits. */
    static final int MAX_PER_BOARD = 10;
    static final int MAX_PER_SERVER = 100;
    /** How long a post waits after a change for more changes before it is redrawn. */
    static final int SETTLE_SECONDS = 2;
    /** A claim not reported back within this is assumed lost and claimed again. */
    static final int CLAIM_TIMEOUT_SECONDS = 120;
    /** A post that cannot be redrawn is retried this often, and removed after failing for a day. */
    static final int RETRY_SECONDS = 300;
    static final int GIVE_UP_HOURS = 24;

    private final JdbcTemplate jdbcTemplate;

    public BoardPostService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** A post the bot should redraw; {@code boardExists} is false once its board was deleted. */
    public record Claimed(long postId, long serverId, long boardId, long channelId, long messageId,
                          boolean boardExists) {
    }

    /** The active boards posted in a channel, by id. */
    public List<Long> boardsPostedIn(long serverId, long channelId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT p.board_id FROM board_posts p JOIN boards b ON b.board_id = p.board_id
                WHERE p.server_id = ? AND p.channel_id = ? AND b.server_id = p.server_id AND NOT b.is_archived
                ORDER BY p.board_id
                """, Long.class, serverId, channelId);
    }

    /** Records a message the bot just posted. It is drawn again at once from the whole board. */
    @Transactional
    public long register(long serverId, long boardId, long channelId, long messageId, long createdBy) {
        Integer onBoard = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM board_posts WHERE board_id = ?", Integer.class, boardId);
        if (onBoard != null && onBoard >= MAX_PER_BOARD) {
            throw new BadRequestException("This board already has " + MAX_PER_BOARD
                    + " posts. Delete one of its messages in Discord first.");
        }
        Integer inServer = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM board_posts WHERE server_id = ?", Integer.class, serverId);
        if (inServer != null && inServer >= MAX_PER_SERVER) {
            throw new BadRequestException("This server already has " + MAX_PER_SERVER
                    + " board posts. Delete some of their messages in Discord first.");
        }
        try {
            Long postId = jdbcTemplate.queryForObject("""
                    INSERT INTO board_posts (server_id, board_id, channel_id, message_id, created_by, dirty_at)
                    VALUES (?, ?, ?, ?, ?, now() - make_interval(secs => ?))
                    RETURNING post_id
                    """, Long.class, serverId, boardId, channelId, messageId, createdBy, SETTLE_SECONDS);
            return postId == null ? 0 : postId;
        } catch (DuplicateKeyException ex) {
            throw new BadRequestException("That message is already a board post");
        }
    }

    /**
     * Marks posts out of date after any change, before the change commits: a board's changes mark
     * its posts, and a server-wide change (its features, say) marks all of the server's. Changes
     * synced from Discord (members, roles) do not show on a post.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onDomainEvent(DomainEvent event) {
        if (event.type().fromDiscordSync() || event.serverId() == null) {
            return;
        }
        if (event.boardId() != null) {
            jdbcTemplate.update("UPDATE board_posts SET dirty_at = now() WHERE board_id = ? AND dirty_at IS NULL",
                    event.boardId());
        } else {
            jdbcTemplate.update("UPDATE board_posts SET dirty_at = now() WHERE server_id = ? AND dirty_at IS NULL",
                    event.serverId());
        }
    }

    /**
     * Up to {@code limit} posts to redraw: out of date for a moment, not waiting to retry, and not
     * already being redrawn (unless that claim was lost). Claiming clears "out of date", so a change
     * made while the bot redraws marks the post again.
     */
    @Transactional
    public List<Claimed> claim(int limit) {
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT post_id FROM board_posts
                    WHERE ((dirty_at IS NOT NULL AND dirty_at <= now() - make_interval(secs => ?)
                            AND (claimed_at IS NULL OR claimed_at < now() - make_interval(secs => ?)))
                        OR (dirty_at IS NULL AND claimed_at < now() - make_interval(secs => ?)))
                      AND (retry_after IS NULL OR retry_after <= now())
                    ORDER BY dirty_at NULLS FIRST
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE board_posts p SET dirty_at = NULL, claimed_at = now()
                FROM due WHERE p.post_id = due.post_id
                RETURNING p.post_id, p.server_id, p.board_id, p.channel_id, p.message_id,
                    EXISTS (SELECT 1 FROM boards b WHERE b.board_id = p.board_id AND b.server_id = p.server_id)
                """, (row, index) -> new Claimed(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4),
                row.getLong(5), row.getBoolean(6)), SETTLE_SECONDS, CLAIM_TIMEOUT_SECONDS, CLAIM_TIMEOUT_SECONDS, limit);
    }

    /**
     * What happened to claimed posts: redrawn; gone (the message or channel no longer exists, or
     * the board was deleted and the message now says so), so the post is removed; or to try again
     * later, until it has failed for a day.
     */
    @Transactional
    public void report(List<Long> done, List<Long> gone, List<Long> retry) {
        if (!done.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE board_posts SET claimed_at = NULL, retry_after = NULL, failing_since = NULL
                    WHERE post_id = ANY (?)
                    """, longArray(done));
        }
        if (!gone.isEmpty()) {
            jdbcTemplate.update("DELETE FROM board_posts WHERE post_id = ANY (?)", longArray(gone));
        }
        if (!retry.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE board_posts SET claimed_at = NULL,
                        dirty_at = COALESCE(dirty_at, now() - make_interval(secs => ?)),
                        failing_since = COALESCE(failing_since, now()),
                        retry_after = now() + make_interval(secs => ?)
                    WHERE post_id = ANY (?)
                    """, SETTLE_SECONDS, RETRY_SECONDS, longArray(retry));
            jdbcTemplate.update("""
                    DELETE FROM board_posts
                    WHERE post_id = ANY (?) AND failing_since < now() - make_interval(hours => ?)
                    """, longArray(retry), GIVE_UP_HOURS);
        }
    }

    private Array longArray(List<Long> values) {
        return jdbcTemplate.execute((java.sql.Connection connection) -> connection.createArrayOf("bigint", values.toArray()));
    }
}
