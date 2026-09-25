package com.kanbancord_api.notify;

import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reminds people about due dates: once when a task is due within a day, and once when it becomes
 * overdue. Reminders go out like other notifications (to the task's people by direct message, and to
 * feeds that switched reminders on).
 *
 * <p>Only for tasks that are not done (a task in its board's last column counts as done), on boards
 * in use, with due dates switched on, in servers the bot is in. Each reminder is sent once per due
 * date; changing the due date brings new reminders. Overdue reminders are only for tasks that became
 * overdue in the last hour, so nothing long past is suddenly announced.
 */
@Component
public class DueReminderScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final NotificationQueue queue;
    private final ServerFeatureService features;

    public DueReminderScheduler(JdbcTemplate jdbcTemplate, NotificationQueue queue, ServerFeatureService features) {
        this.jdbcTemplate = jdbcTemplate;
        this.queue = queue;
        this.features = features;
    }

    /**
     * Due dates are stored as UTC times without a zone; "now" must be too, whatever the time zone of
     * the database connection.
     */
    private static final String NOW_UTC = "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')";

    private record Candidate(long taskId, long boardId, long serverId, java.time.LocalDateTime dueDate) {
    }

    @Scheduled(fixedDelayString = "${kanbancord.notifications.reminder-check-ms:60000}",
            initialDelayString = "${kanbancord.notifications.reminder-first-check-ms:30000}")
    public void remind() {
        queueReminders("DUE_SOON", "t.due_date > " + NOW_UTC + " AND t.due_date <= " + NOW_UTC + " + INTERVAL '1 day'");
        queueReminders("OVERDUE", "t.due_date <= " + NOW_UTC + " AND t.due_date > " + NOW_UTC + " - INTERVAL '1 hour'");
    }

    /** @return how many reminders were queued */
    int queueReminders(String kind, String window) {
        List<Candidate> candidates = jdbcTemplate.query("""
                SELECT t.task_id, t.board_id, b.server_id, t.due_date
                FROM tasks t
                JOIN boards b ON b.board_id = t.board_id
                JOIN servers s ON s.server_id = b.server_id
                WHERE t.due_date IS NOT NULL AND %s
                  AND NOT b.is_archived AND s.bot_present
                  AND t.column_id <> (SELECT c.column_id FROM columns c WHERE c.board_id = t.board_id
                                      ORDER BY c.position DESC LIMIT 1)
                  AND NOT EXISTS (SELECT 1 FROM task_due_reminders r
                                  WHERE r.task_id = t.task_id AND r.kind = ? AND r.due_date = t.due_date)
                LIMIT 500
                """.formatted(window), (rs, row) -> new Candidate(rs.getLong("task_id"), rs.getLong("board_id"),
                rs.getLong("server_id"), rs.getTimestamp("due_date").toLocalDateTime()), kind);
        int queued = 0;
        for (Candidate candidate : candidates) {
            if (features.isEnabled(candidate.serverId(), candidate.boardId(), Feature.DUE_DATES)
                    && queue.enqueueReminder(candidate.serverId(), candidate.taskId(), kind, candidate.dueDate())) {
                queued++;
            }
        }
        return queued;
    }
}
