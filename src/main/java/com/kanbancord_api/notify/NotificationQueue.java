package com.kanbancord_api.notify;

import com.kanbancord_api.audit.AuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Changes waiting for the bot to deliver. Each audit log entry that can be announced joins the open
 * group for what it concerns (all changes to one task, say); a group becomes due a short while after
 * its first change, so quick successive edits go out as one message.
 *
 * <p>The bot claims due groups, delivers them and reports back. A claimed group that is not reported
 * within a few minutes (the bot restarted, say) is claimed again; one that keeps failing is dropped.
 */
@Service
public class NotificationQueue {

    /** A claim not reported back within this is assumed lost. */
    static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    /** Changes older than this when they could be delivered are dropped instead. */
    static final Duration MAX_AGE = Duration.ofHours(1);

    private final JdbcTemplate jdbcTemplate;
    /** How long a group stays open for more changes. */
    private final Duration groupWindow;

    public NotificationQueue(JdbcTemplate jdbcTemplate,
                             @Value("${kanbancord.notifications.group-window-seconds:30}") long groupWindowSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.groupWindow = Duration.ofSeconds(groupWindowSeconds);
    }

    /** What a queued group is. The database allows each kind only its own fields (V25). */
    public enum Kind {
        /** Changes people made on a server: its audit entries, in order. */
        CHANGES,
        /** A task is due soon or overdue: no audit entries, nobody did anything. */
        REMINDER,
        /** Someone asks for the website in another language; for the developers only, about no server. */
        LANGUAGE_REQUEST
    }

    /** Who asked for which language, and what they added (may be null). */
    public record LanguageRequest(long userId, String language, String note) {
    }

    /**
     * A claimed group: its id and kind. Changes have a server and audit entries; a reminder has a
     * server, the task it is about and why ({@code reminderKind} DUE_SOON or OVERDUE); a language
     * request has only the request.
     */
    public record Batch(long batchId, Kind kind, Long serverId, List<Long> auditIds, String reminderKind,
                        Long reminderTaskId, LocalDateTime reminderDue, LanguageRequest request) {

        public Batch(long batchId, long serverId, List<Long> auditIds) {
            this(batchId, Kind.CHANGES, serverId, auditIds, null, null, null, null);
        }

        public boolean isReminder() {
            return kind == Kind.REMINDER;
        }
    }

    /** Language requests one person may make in a day. */
    static final int LANGUAGE_REQUESTS_PER_DAY = 3;
    /** The first key of the advisory lock that counts one person's language requests. */
    private static final int LANGUAGE_REQUEST_LOCK = 72_001;

    /**
     * Queues a reminder about a task's due date, once per task, kind and due date: returns false when
     * that reminder was already sent.
     */
    @Transactional
    public boolean enqueueReminder(long serverId, long taskId, String kind, LocalDateTime dueDate) {
        int recorded = jdbcTemplate.update("""
                INSERT INTO task_due_reminders (task_id, kind, due_date) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, taskId, kind, Timestamp.valueOf(dueDate));
        if (recorded == 0) {
            return false;
        }
        jdbcTemplate.update("""
                INSERT INTO notification_queue (kind, server_id, group_key, audit_ids, deliver_after, claimed_at,
                    reminder_kind, reminder_task_id, reminder_due)
                VALUES ('REMINDER', ?, ?, '{}', CURRENT_TIMESTAMP, NULL, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, serverId, "reminder:" + taskId + ":" + kind + ":" + dueDate, kind, taskId, Timestamp.valueOf(dueDate));
        return true;
    }

    /**
     * Adds an audit entry to its group, in the transaction that recorded it, so a change that is
     * rolled back is never announced. Entries that can never be announced still join: the audit
     * channel mirrors every entry.
     */
    public void enqueue(AuditLog log) {
        Long serverId = log.getServer() == null ? null : log.getServer().getServerId();
        if (serverId == null || log.getLogId() == null) {
            return;
        }
        Long boardId = log.getBoard() == null ? null : log.getBoard().getBoardId();
        Long taskId = AuditClassifier.taskIdOf(log.getEntityType(), log.getAction(), log.getEntityId(), log.getChanges());
        String groupKey = AuditClassifier.groupKeyOf(serverId, boardId, taskId, log.getEntityType(), log.getEntityId(),
                log.getLogId());
        jdbcTemplate.update("""
                INSERT INTO notification_queue (kind, server_id, group_key, audit_ids, deliver_after)
                VALUES ('CHANGES', ?, ?, ARRAY[?]::BIGINT[], CURRENT_TIMESTAMP + CAST(? AS INTERVAL))
                ON CONFLICT (group_key) WHERE claimed_at IS NULL
                DO UPDATE SET audit_ids = notification_queue.audit_ids || EXCLUDED.audit_ids
                """, serverId, groupKey, log.getLogId(), groupWindow.toSeconds() + " seconds");
    }

    /**
     * Queues a request for the website in another language, for the bot to pass on to the developers.
     * Returns false, queueing nothing, when the person already made {@link #LANGUAGE_REQUESTS_PER_DAY}
     * requests in the last day.
     */
    @Transactional
    public boolean enqueueLanguageRequest(long userId, String language, String note) {
        // One person's requests are counted one at a time, so quick repeats cannot slip past the limit.
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, LANGUAGE_REQUEST_LOCK, (int) (userId % Integer.MAX_VALUE));
        Integer recent = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification_queue
                WHERE kind = 'LANGUAGE_REQUEST' AND request_user_id = ?
                  AND created_at > CURRENT_TIMESTAMP - INTERVAL '1 day'
                """, Integer.class, userId);
        if (recent != null && recent >= LANGUAGE_REQUESTS_PER_DAY) {
            return false;
        }
        jdbcTemplate.update("""
                INSERT INTO notification_queue (kind, server_id, group_key, audit_ids, deliver_after,
                    request_user_id, request_language, request_note)
                VALUES ('LANGUAGE_REQUEST', NULL, ?, '{}', CURRENT_TIMESTAMP, ?, ?, ?)
                """, "language-request:" + UUID.randomUUID(), userId, language, note);
        return true;
    }

    /**
     * Claims up to {@code limit} due groups for delivery, oldest first. Groups claimed but never
     * reported back are due again after a while. Safe to call from several places at once: each
     * group goes to one caller.
     */
    @Transactional
    public List<Batch> claim(int limit) {
        // Old news is not worth posting: after the bot was away for a while, what it missed is dropped
        // rather than delivered all at once.
        jdbcTemplate.update("""
                UPDATE notification_queue SET delivered_at = CURRENT_TIMESTAMP
                WHERE delivered_at IS NULL AND created_at < CURRENT_TIMESTAMP - CAST(? AS INTERVAL)
                """, MAX_AGE.toSeconds() + " seconds");
        return jdbcTemplate.query("""
                UPDATE notification_queue SET claimed_at = CURRENT_TIMESTAMP, attempts = attempts + 1
                WHERE batch_id IN (
                    SELECT batch_id FROM notification_queue
                    WHERE delivered_at IS NULL AND deliver_after <= CURRENT_TIMESTAMP AND attempts < ?
                      AND (claimed_at IS NULL OR claimed_at < CURRENT_TIMESTAMP - CAST(? AS INTERVAL))
                    ORDER BY deliver_after
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING batch_id, kind, server_id, audit_ids, reminder_kind, reminder_task_id, reminder_due,
                    request_user_id, request_language, request_note
                """, (rs, row) -> new Batch(rs.getLong("batch_id"), Kind.valueOf(rs.getString("kind")),
                        (Long) rs.getObject("server_id"),
                        List.of((Long[]) rs.getArray("audit_ids").getArray()), rs.getString("reminder_kind"),
                        (Long) rs.getObject("reminder_task_id"),
                        rs.getTimestamp("reminder_due") == null ? null : rs.getTimestamp("reminder_due").toLocalDateTime(),
                        rs.getObject("request_user_id") == null ? null : new LanguageRequest(rs.getLong("request_user_id"),
                                rs.getString("request_language"), rs.getString("request_note"))),
                MAX_ATTEMPTS, CLAIM_TIMEOUT.toSeconds() + " seconds", limit);
    }

    /** The group was delivered (or there was nothing to deliver). */
    public void markDelivered(long batchId) {
        jdbcTemplate.update("UPDATE notification_queue SET delivered_at = CURRENT_TIMESTAMP WHERE batch_id = ?", batchId);
    }

    /** Delivery failed; try again after a pause that grows with each attempt. */
    public void release(long batchId) {
        jdbcTemplate.update("""
                UPDATE notification_queue
                SET claimed_at = NULL, deliver_after = CURRENT_TIMESTAMP + attempts * INTERVAL '1 minute'
                WHERE batch_id = ? AND delivered_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM notification_queue open
                                  WHERE open.group_key = notification_queue.group_key AND open.claimed_at IS NULL)
                """, batchId);
    }

    /**
     * Keeps the queue small: after a week a group is of no use, delivered or not (undelivered ones are
     * long past being worth posting, see {@link #MAX_AGE}).
     */
    @Scheduled(cron = "0 37 4 * * *")
    public void cleanUp() {
        jdbcTemplate.update("DELETE FROM notification_queue WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'");
    }
}
