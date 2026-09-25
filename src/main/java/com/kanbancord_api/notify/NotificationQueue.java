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

    /**
     * A claimed group: its id, its server and its audit entries in order; or, for a reminder, which
     * task it is about and why ({@code reminderKind} DUE_SOON or OVERDUE), with no audit entries.
     */
    public record Batch(long batchId, long serverId, List<Long> auditIds, String reminderKind, Long reminderTaskId,
                        LocalDateTime reminderDue) {

        public Batch(long batchId, long serverId, List<Long> auditIds) {
            this(batchId, serverId, auditIds, null, null, null);
        }

        public boolean isReminder() {
            return reminderKind != null;
        }
    }

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
                INSERT INTO notification_queue (server_id, group_key, audit_ids, deliver_after, claimed_at,
                    reminder_kind, reminder_task_id, reminder_due)
                VALUES (?, ?, '{}', CURRENT_TIMESTAMP, NULL, ?, ?, ?)
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
                INSERT INTO notification_queue (server_id, group_key, audit_ids, deliver_after)
                VALUES (?, ?, ARRAY[?]::BIGINT[], CURRENT_TIMESTAMP + CAST(? AS INTERVAL))
                ON CONFLICT (group_key) WHERE claimed_at IS NULL
                DO UPDATE SET audit_ids = notification_queue.audit_ids || EXCLUDED.audit_ids
                """, serverId, groupKey, log.getLogId(), groupWindow.toSeconds() + " seconds");
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
                RETURNING batch_id, server_id, audit_ids, reminder_kind, reminder_task_id, reminder_due
                """, (rs, row) -> new Batch(rs.getLong("batch_id"), rs.getLong("server_id"),
                        List.of((Long[]) rs.getArray("audit_ids").getArray()), rs.getString("reminder_kind"),
                        (Long) rs.getObject("reminder_task_id"),
                        rs.getTimestamp("reminder_due") == null ? null : rs.getTimestamp("reminder_due").toLocalDateTime()),
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
