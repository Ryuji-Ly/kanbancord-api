package com.kanbancord_api.notify;

import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reminds people about due dates: before a task is due, at the times the server, the board or each person chose
 * ({@link ReminderTimes}), and once when it becomes overdue. Reminders go out like other notifications (to the
 * task's people by direct message, and to feeds that switched reminders on).
 *
 * <p>The shared reminder follows the board's times (or the server's) and goes to feeds and to everyone without
 * times of their own; someone with their own times gets a direct message of their own at those times instead.
 * When several times have passed at once (a task made due in two hours, with reminders a day and three hours
 * before), only the latest applies: one reminder, not two.
 *
 * <p>A time is skipped once a later reminder went out for the same due date, so changing the times does not
 * send an early reminder after a later one.
 *
 * <p>Only for tasks that are not done (a task in its board's last column counts as done), on boards in use, with
 * due dates switched on, in servers the bot is in. Each reminder is sent once per due date; changing the due date
 * brings new reminders. Overdue reminders are only for tasks that became overdue in the last hour, so nothing long
 * past is suddenly announced.
 */
@Component
public class DueReminderScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final NotificationQueue queue;
    private final ServerFeatureService features;
    private final ReminderTimes times;

    public DueReminderScheduler(JdbcTemplate jdbcTemplate, NotificationQueue queue, ServerFeatureService features,
            ReminderTimes times) {
        this.jdbcTemplate = jdbcTemplate;
        this.queue = queue;
        this.features = features;
        this.times = times;
    }

    /**
     * Due dates are stored as UTC times without a zone; "now" must be too, whatever the time zone of
     * the database connection.
     */
    private static final String NOW_UTC = "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')";
    /** Tasks looked at per run; the rest wait a minute. */
    private static final int BATCH = 2000;

    /** The reminder a day before keeps the name it had when it was the only one, so none is sent twice. */
    static String kindFor(int minutes) {
        return minutes == 1440 ? "DUE_SOON" : "BEFORE_" + minutes;
    }

    /** The minutes before the due date of a reminder kind, or null for one that is not before it (OVERDUE). */
    static Integer minutesOf(String kind) {
        if ("DUE_SOON".equals(kind)) {
            return 1440;
        }
        return kind != null && kind.startsWith("BEFORE_") ? Integer.valueOf(kind.substring("BEFORE_".length())) : null;
    }

    /**
     * The time that applies now: the smallest of the times that have been reached, or null when none has.
     *
     * @param minutesLeft how long until the task is due
     */
    static Integer reached(List<Integer> minutes, double minutesLeft) {
        Integer reached = null;
        for (int minute : minutes) {
            if (minutesLeft <= minute && (reached == null || minute < reached)) {
                reached = minute;
            }
        }
        return reached;
    }

    private record Candidate(long taskId, long boardId, long serverId, LocalDateTime dueDate, double minutesLeft) {
    }

    @Scheduled(fixedDelayString = "${kanbancord.notifications.reminder-check-ms:60000}",
            initialDelayString = "${kanbancord.notifications.reminder-first-check-ms:30000}")
    public void remind() {
        queueBefore();
        queueOverdue();
    }

    /** @return how many reminders were queued */
    int queueBefore() {
        int longest = times.longest();
        if (longest <= 0) {
            return 0;
        }
        List<Candidate> candidates = candidates(
                "t.due_date > " + NOW_UTC + " AND t.due_date <= " + NOW_UTC + " + " + longest + " * INTERVAL '1 minute'");
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<String, Integer> sent = sent(candidates);
        Set<Long> boardIds = new LinkedHashSet<>();
        candidates.forEach(candidate -> boardIds.add(candidate.boardId()));
        Map<Long, Map<Long, List<Integer>>> personal = times.personalByBoard(boardIds);
        Map<Long, Set<Long>> people = people(candidates, personal);
        Map<Long, List<Integer>> shared = new LinkedHashMap<>();

        int queued = 0;
        for (Candidate candidate : candidates) {
            if (!features.isEnabled(candidate.serverId(), candidate.boardId(), Feature.DUE_DATES)) {
                continue;
            }
            List<Integer> boardTimes = shared.computeIfAbsent(candidate.boardId(),
                    id -> times.shared(candidate.serverId(), id));
            queued += queue(candidate, reached(boardTimes, candidate.minutesLeft()), null, sent);
            Integer sharedSent = sent.get(key(candidate.taskId(), candidate.dueDate(), 0));

            Map<Long, List<Integer>> own = personal.getOrDefault(candidate.boardId(), Map.of());
            for (Long userId : people.getOrDefault(candidate.taskId(), Set.of())) {
                List<Integer> mine = own.get(userId);
                if (mine != null) {
                    // A reminder they had from the shared ones, before choosing their own, counts too.
                    queued += queue(candidate, reached(mine, candidate.minutesLeft()), userId, sent, sharedSent);
                }
            }
        }
        return queued;
    }

    /** @return how many reminders were queued */
    int queueOverdue() {
        int queued = 0;
        for (Candidate candidate : candidates("t.due_date <= " + NOW_UTC + " AND t.due_date > " + NOW_UTC
                + " - INTERVAL '1 hour'")) {
            if (features.isEnabled(candidate.serverId(), candidate.boardId(), Feature.DUE_DATES)
                    && queue.enqueueReminder(candidate.serverId(), candidate.taskId(), "OVERDUE", candidate.dueDate(),
                    null)) {
                queued++;
            }
        }
        return queued;
    }

    private int queue(Candidate candidate, Integer minutes, Long userId, Map<String, Integer> sent) {
        return queue(candidate, minutes, userId, sent, null);
    }

    /**
     * Queues the reminder at {@code minutes} before the due date unless this one, or a later one, already went out
     * for this due date.
     */
    private int queue(Candidate candidate, Integer minutes, Long userId, Map<String, Integer> sent, Integer alsoSent) {
        if (minutes == null) {
            return 0;
        }
        Integer latestSent = sent.get(key(candidate.taskId(), candidate.dueDate(), userId == null ? 0 : userId));
        if ((latestSent != null && latestSent <= minutes) || (alsoSent != null && alsoSent <= minutes)) {
            return 0;
        }
        boolean queued = queue.enqueueReminder(candidate.serverId(), candidate.taskId(), kindFor(minutes),
                candidate.dueDate(), userId);
        if (queued) {
            sent.merge(key(candidate.taskId(), candidate.dueDate(), userId == null ? 0 : userId), minutes, Math::min);
        }
        return queued ? 1 : 0;
    }

    private List<Candidate> candidates(String window) {
        return jdbcTemplate.query("""
                SELECT t.task_id, t.board_id, b.server_id, t.due_date,
                       EXTRACT(EPOCH FROM (t.due_date - %s)) / 60 AS minutes_left
                FROM tasks t
                JOIN boards b ON b.board_id = t.board_id
                JOIN servers s ON s.server_id = b.server_id
                WHERE t.due_date IS NOT NULL AND %s
                  AND NOT t.is_archived AND NOT b.is_archived AND s.bot_present
                  AND t.column_id <> (SELECT c.column_id FROM columns c WHERE c.board_id = t.board_id
                                      ORDER BY c.position DESC LIMIT 1)
                ORDER BY t.due_date
                LIMIT %d
                """.formatted(NOW_UTC, window, BATCH), (rs, row) -> new Candidate(rs.getLong("task_id"),
                rs.getLong("board_id"), rs.getLong("server_id"), rs.getTimestamp("due_date").toLocalDateTime(),
                rs.getDouble("minutes_left")));
    }

    /**
     * For each task, due date and person (0 for the shared reminder), the latest reminder already sent before the
     * due date, in minutes before it.
     */
    private Map<String, Integer> sent(List<Candidate> candidates) {
        Map<String, Integer> sent = new HashMap<>();
        jdbcTemplate.query("SELECT task_id, kind, due_date, user_id FROM task_due_reminders WHERE task_id = ANY (?)",
                rs -> {
                    Integer minutes = minutesOf(rs.getString("kind"));
                    if (minutes != null) {
                        sent.merge(key(rs.getLong("task_id"), rs.getTimestamp("due_date").toLocalDateTime(),
                                rs.getLong("user_id")), minutes, Math::min);
                    }
                }, (Object) candidates.stream().map(Candidate::taskId).toArray(Long[]::new));
        return sent;
    }

    private static String key(long taskId, LocalDateTime dueDate, long userId) {
        return taskId + ":" + Timestamp.valueOf(dueDate) + ":" + userId;
    }

    /**
     * For each task, the people on it (assigned, its creator, followers and commenters) who have reminder times of
     * their own there. Whether they want reminders at all is decided when the message is made, as for everyone.
     */
    private Map<Long, Set<Long>> people(List<Candidate> candidates, Map<Long, Map<Long, List<Integer>>> personal) {
        Map<Long, Set<Long>> people = new LinkedHashMap<>();
        Set<Long> userIds = new HashSet<>();
        personal.values().forEach(byUser -> userIds.addAll(byUser.keySet()));
        if (userIds.isEmpty()) {
            return people;
        }
        List<Long> taskIds = new ArrayList<>();
        candidates.forEach(candidate -> taskIds.add(candidate.taskId()));
        jdbcTemplate.query("""
                SELECT task_id, user_id FROM task_assignments WHERE task_id = ANY (?) AND user_id = ANY (?)
                UNION SELECT task_id, created_by FROM tasks WHERE task_id = ANY (?) AND created_by = ANY (?)
                UNION SELECT task_id, user_id FROM task_followers WHERE task_id = ANY (?) AND user_id = ANY (?)
                UNION SELECT task_id, user_id FROM task_comments
                      WHERE task_id = ANY (?) AND user_id = ANY (?) AND deleted_at IS NULL
                """, rs -> {
            people.computeIfAbsent(rs.getLong(1), id -> new LinkedHashSet<>()).add(rs.getLong(2));
        }, ids(taskIds), ids(userIds), ids(taskIds), ids(userIds), ids(taskIds), ids(userIds), ids(taskIds),
                ids(userIds));
        return people;
    }

    private static Object ids(java.util.Collection<Long> ids) {
        return ids.toArray(Long[]::new);
    }
}
