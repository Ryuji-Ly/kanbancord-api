package com.kanbancord_api.notify;

import com.kanbancord_api.audit.AuditLog;
import com.kanbancord_api.audit.AuditLogRepository;
import com.kanbancord_api.audit.AuditLogResponse;
import com.kanbancord_api.permission.PermissionEvaluationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Works out who hears about a group of changes, and where: the feeds whose channel and events match,
 * who each of them mentions, the audit channel, and who gets a direct message. The bot then sends
 * it; only the bot can tell whether someone mentioned in a channel can actually see that channel,
 * so it makes the final call for direct messages sent "only if not pinged".
 *
 * <p>Worked out at delivery time, from the settings as they are then; nobody is ever told about a
 * change they made themselves.
 */
@Service
public class NotificationRouter {

    private final AuditLogRepository auditLogRepository;
    private final NotificationSettingsService settings;
    private final PermissionEvaluationService permissions;
    private final JdbcTemplate jdbcTemplate;
    private final TaskThreadService threads;

    public NotificationRouter(AuditLogRepository auditLogRepository, NotificationSettingsService settings,
                              PermissionEvaluationService permissions, JdbcTemplate jdbcTemplate,
                              TaskThreadService threads) {
        this.auditLogRepository = auditLogRepository;
        this.settings = settings;
        this.permissions = permissions;
        this.jdbcTemplate = jdbcTemplate;
        this.threads = threads;
    }

    public record BoardRef(Long boardId, String name) {
    }

    /** The task the changes are about; {@code deleted} when it no longer exists. */
    public record TaskRef(Long taskId, String title, boolean deleted) {
    }

    /** Ids and names the entries refer to, so they can be shown by name. */
    public record Names(Map<String, String> columns, Map<String, String> labels, Map<String, String> priorities) {
    }

    /**
     * A message to one channel. {@code kind} is FEED or AUDIT; the audit channel never mentions anyone.
     * {@code interactive}: a feed post that shows the whole task with buttons to change it, for a task
     * that still exists and a feed (of those sharing the channel) that wants it.
     */
    public record ChannelDelivery(String channelId, String kind, List<Long> entryIds, List<String> mentionUserIds,
                                  List<String> mentionRoleIds, boolean interactive) {
    }

    /** A direct message. {@code mode} UNLESS_PINGED leaves it to the bot to skip it if a channel already did. */
    public record DirectMessage(String userId, List<Long> entryIds, String mode) {
    }

    /**
     * The task's thread in a feed channel, for a board with threads on. {@code threadId} is null when
     * the bot is to make it (it then reports it back). {@code updates} says where the feed's posts for
     * that channel go once there is a thread: BOTH, THREAD or CHANNEL. {@code close}: the task was
     * deleted or archived, so its thread is archived. {@code members}: who a private thread includes
     * (the task's creator and assignees).
     */
    public record ThreadPlan(String channelId, String threadId, boolean privateThread, String updates, String name,
                             boolean close, List<String> members) {
    }

    /**
     * A request for the website in another language, for the developers: who asked (their id and
     * name), the language's code (BCP 47, such as pt-BR) and what they added, if anything.
     */
    public record LanguageRequestPlan(String userId, String userName, String language, String note) {
    }

    /**
     * What to deliver for one queued group. {@code kind} is that of the group (CHANGES, REMINDER or
     * LANGUAGE_REQUEST); the bot delivers each kind its own way, and refuses kinds it does not know.
     * A language request has only {@code request}: no server, channels, direct messages or thread.
     */
    public record Plan(long batchId, String kind, String serverId, BoardRef board, TaskRef task,
                       List<AuditLogResponse> entries, Names names, List<ChannelDelivery> channels,
                       List<DirectMessage> directMessages, ThreadPlan thread, LanguageRequestPlan request) {

        public boolean isEmpty() {
            return channels.isEmpty() && directMessages.isEmpty() && thread == null && request == null;
        }
    }

    /** An entry with what it means for notifications. */
    private record Entry(AuditLogResponse log, Set<NotificationEvent> events, Long actorId, Long subjectId) {

        static Entry of(AuditLogResponse log) {
            return new Entry(log, AuditClassifier.eventsOf(log.action(), log.changes()), log.userId(),
                    AuditClassifier.subjectIdOf(log.action(), log.changes()));
        }
    }

    @Transactional(readOnly = true)
    public Plan route(NotificationQueue.Batch batch) {
        return switch (batch.kind()) {
            case LANGUAGE_REQUEST -> languageRequest(batch);
            case CHANGES, REMINDER -> serverChanges(batch);
        };
    }

    /** Only for the developers: the bot sends it to them alone, never to a server's channels or people. */
    private Plan languageRequest(NotificationQueue.Batch batch) {
        NotificationQueue.LanguageRequest request = batch.request();
        List<String> names = jdbcTemplate.queryForList(
                "SELECT COALESCE(global_name, username) FROM users WHERE user_id = ?", String.class, request.userId());
        return new Plan(batch.batchId(), batch.kind().name(), null, null, null, List.of(),
                new Names(Map.of(), Map.of(), Map.of()), List.of(), List.of(), null,
                new LanguageRequestPlan(String.valueOf(request.userId()), names.isEmpty() ? null : names.get(0),
                        request.language(), request.note()));
    }

    private Plan serverChanges(NotificationQueue.Batch batch) {
        Long serverId = batch.serverId();
        if (!botPresent(serverId)) {
            return empty(batch);
        }
        List<Entry> entries = batch.isReminder()
                ? reminderEntry(batch).map(Entry::of).stream().toList()
                : auditLogRepository.findAllById(batch.auditIds()).stream()
                        .sorted(Comparator.comparing(AuditLog::getLogId))
                        .map(log -> Entry.of(AuditLogResponse.from(log)))
                        .toList();
        if (entries.isEmpty()) {
            return empty(batch);
        }

        AuditLogResponse first = entries.get(0).log();
        Long boardId = first.boardId();
        BoardRef board = boardId == null ? null : new BoardRef(boardId, first.boardName());
        Long taskId = AuditClassifier.taskIdOf(first.entityType(), first.action(), first.entityId(), first.changes());
        TaskContext task = taskId == null ? null : taskContext(taskId, entries);

        List<ChannelDelivery> channels = new ArrayList<>(feedDeliveries(serverId, boardId, entries, task));
        // The audit channel records what people did; a reminder is only time passing.
        if (!batch.isReminder()) {
            settings.auditChannel(serverId).ifPresent(channelId -> channels.add(new ChannelDelivery(String.valueOf(channelId),
                    "AUDIT", entries.stream().map(entry -> entry.log().logId()).toList(), List.of(), List.of(), false)));
        }
        List<DirectMessage> directMessages = task == null || boardId == null
                ? List.of()
                : directMessages(serverId, boardId, entries, task);

        return new Plan(batch.batchId(), batch.kind().name(), String.valueOf(serverId), board,
                task == null ? null : new TaskRef(taskId, task.title(), task.deleted()),
                entries.stream().map(Entry::log).toList(),
                boardId == null ? new Names(Map.of(), Map.of(), Map.of()) : names(boardId),
                channels, directMessages, threadPlan(serverId, boardId, taskId, task), null);
    }

    // ── Task threads ─────────────────────────────────────────────────────────

    /** The task's thread, for a board with threads on; or to close an existing one when the task goes. */
    private ThreadPlan threadPlan(Long serverId, Long boardId, Long taskId, TaskContext task) {
        if (boardId == null || taskId == null || task == null) {
            return null;
        }
        java.util.Optional<TaskThreadService.TaskThread> existing = threads.thread(taskId);
        boolean close = task.deleted() || archived(taskId);
        java.util.Optional<TaskThreadService.Settings> on = threads.active(serverId, boardId);
        if (on.isEmpty()) {
            // Switched off: no new threads and nothing posted in old ones, but a task's thread still closes with it.
            return existing.filter(thread -> close)
                    .map(thread -> new ThreadPlan(String.valueOf(thread.channelId()), String.valueOf(thread.threadId()),
                            thread.privateThread(), TaskThreadService.Updates.CHANNEL.name(), task.title(), true, List.of()))
                    .orElse(null);
        }
        TaskThreadService.Settings settings = on.get();
        // A thread in another channel (the board's threads moved) is left; the task gets one in the new channel.
        TaskThreadService.TaskThread thread = existing
                .filter(found -> found.channelId().equals(settings.channelId())).orElse(null);
        if (thread == null && close) {
            return null;
        }
        boolean privateThread = thread != null ? thread.privateThread() : settings.privateThreads();
        List<String> members = new ArrayList<>();
        if (privateThread) {
            if (task.creatorId() != null) {
                members.add(String.valueOf(task.creatorId()));
            }
            task.assignees().stream().map(String::valueOf).filter(id -> !members.contains(id)).forEach(members::add);
        }
        return new ThreadPlan(String.valueOf(settings.channelId()), thread == null ? null : String.valueOf(thread.threadId()),
                privateThread, settings.updates().name(), task.title(), close, members);
    }

    private boolean archived(Long taskId) {
        List<Boolean> archived = jdbcTemplate.queryForList("SELECT is_archived FROM tasks WHERE task_id = ?",
                Boolean.class, taskId);
        return !archived.isEmpty() && Boolean.TRUE.equals(archived.get(0));
    }

    /**
     * A reminder as an entry: made up, since nobody did anything, and only while it still applies (the
     * task exists, still has that due date, is not done, and its board is in use with due dates on).
     * A task in its board's last column counts as done.
     */
    private java.util.Optional<AuditLogResponse> reminderEntry(NotificationQueue.Batch batch) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT t.board_id, b.name AS board_name, t.due_date,
                       t.column_id = (SELECT c.column_id FROM columns c WHERE c.board_id = t.board_id
                                      ORDER BY c.position DESC LIMIT 1) AS done,
                       b.is_archived
                FROM tasks t JOIN boards b ON b.board_id = t.board_id
                WHERE t.task_id = ?
                """, batch.reminderTaskId());
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        Object due = row.get("due_date");
        boolean sameDue = due instanceof java.sql.Timestamp stamp && stamp.toLocalDateTime().equals(batch.reminderDue());
        if (!sameDue || Boolean.TRUE.equals(row.get("done")) || Boolean.TRUE.equals(row.get("is_archived"))) {
            return java.util.Optional.empty();
        }
        String action = "OVERDUE".equals(batch.reminderKind()) ? AuditClassifier.OVERDUE_ACTION : AuditClassifier.DUE_SOON_ACTION;
        return java.util.Optional.of(new AuditLogResponse(-batch.batchId(), batch.serverId(),
                ((Number) row.get("board_id")).longValue(), (String) row.get("board_name"), null, null, null, null,
                action, "TASK", batch.reminderTaskId(), "SYSTEM", Map.of("dueDate", batch.reminderDue().toString()),
                java.time.LocalDateTime.now()));
    }

    private Plan empty(NotificationQueue.Batch batch) {
        return new Plan(batch.batchId(), batch.kind().name(), String.valueOf(batch.serverId()), null, null, List.of(),
                new Names(Map.of(), Map.of(), Map.of()), List.of(), List.of(), null, null);
    }

    // ── Feeds ────────────────────────────────────────────────────────────────

    /** Feeds sharing a channel are posted there once, with everything either of them wants. */
    private List<ChannelDelivery> feedDeliveries(Long serverId, Long boardId, List<Entry> entries, TaskContext task) {
        Map<Long, LinkedHashSet<Long>> entriesByChannel = new LinkedHashMap<>();
        Map<Long, LinkedHashSet<String>> usersByChannel = new LinkedHashMap<>();
        Map<Long, LinkedHashSet<String>> rolesByChannel = new LinkedHashMap<>();
        Set<Long> interactiveChannels = new java.util.HashSet<>();

        for (NotificationSettingsService.Feed feed : settings.feeds(serverId)) {
            if (boardId == null || !feed.covers(boardId)) {
                continue;
            }
            for (Entry entry : entries) {
                List<NotificationEvent> wanted = entry.events().stream().filter(event -> feed.wants(boardId, event)).toList();
                if (wanted.isEmpty()) {
                    continue;
                }
                entriesByChannel.computeIfAbsent(feed.channelId(), id -> new LinkedHashSet<>()).add(entry.log().logId());
                if (feed.interactive() && task != null && !task.deleted()) {
                    interactiveChannels.add(feed.channelId());
                }
                LinkedHashSet<String> users = usersByChannel.computeIfAbsent(feed.channelId(), id -> new LinkedHashSet<>());
                LinkedHashSet<String> roles = rolesByChannel.computeIfAbsent(feed.channelId(), id -> new LinkedHashSet<>());
                for (NotificationEvent event : wanted) {
                    if (!feed.mentions(boardId, event)) {
                        continue;
                    }
                    if (event.isAboutAssignee() && entry.subjectId() != null) {
                        users.add(String.valueOf(entry.subjectId()));
                    } else if (event == NotificationEvent.ROLE_ASSIGNED || event == NotificationEvent.ROLE_UNASSIGNED) {
                        if (feed.mentionRoles() && entry.subjectId() != null) {
                            roles.add(String.valueOf(entry.subjectId()));
                        }
                    } else if (task != null) {
                        task.assignees().forEach(id -> users.add(String.valueOf(id)));
                        if (feed.mentionRoles()) {
                            task.roles().forEach(id -> roles.add(String.valueOf(id)));
                        }
                    }
                }
                // Nobody is pinged about their own change.
                if (entry.actorId() != null) {
                    users.remove(String.valueOf(entry.actorId()));
                }
            }
        }

        List<ChannelDelivery> deliveries = new ArrayList<>();
        entriesByChannel.forEach((channelId, ids) -> deliveries.add(new ChannelDelivery(String.valueOf(channelId), "FEED",
                List.copyOf(ids), List.copyOf(usersByChannel.get(channelId)), List.copyOf(rolesByChannel.get(channelId)),
                interactiveChannels.contains(channelId))));
        return deliveries;
    }

    // ── Direct messages ──────────────────────────────────────────────────────

    private List<DirectMessage> directMessages(Long serverId, Long boardId, List<Entry> entries, TaskContext task) {
        // Everyone who might want to hear: the task's people, and whoever was (un)assigned.
        Set<Long> candidates = new LinkedHashSet<>(task.related());
        entries.stream().filter(entry -> entry.events().stream().anyMatch(NotificationEvent::isAboutAssignee))
                .map(Entry::subjectId).filter(Objects::nonNull).forEach(candidates::add);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, NotificationSettingsService.UserSettings> preferences = settings.userSettings(candidates);
        List<DirectMessage> messages = new ArrayList<>();
        for (Long userId : candidates) {
            NotificationSettingsService.UserSettings mine = preferences.get(userId);
            boolean related = task.assignees().contains(userId) || userId.equals(task.creatorId())
                    || (mine.includeCommented() && task.commenters().contains(userId))
                    || (mine.includeFollowed() && task.followers().contains(userId));
            List<Long> ids = new ArrayList<>();
            for (Entry entry : entries) {
                if (userId.equals(entry.actorId())) {
                    continue;
                }
                boolean wanted = entry.events().stream().anyMatch(event -> mine.wants(serverId, event)
                        && (event.isAboutAssignee() ? userId.equals(entry.subjectId()) : related));
                if (wanted) {
                    ids.add(entry.log().logId());
                }
            }
            // Only people who can still see the board hear about it.
            if (!ids.isEmpty() && permissions.isAllowed(serverId, boardId, userId, "VIEW_BOARD")) {
                messages.add(new DirectMessage(String.valueOf(userId), ids, mine.dmMode().name()));
            }
        }
        return messages;
    }

    // ── What the entries refer to ────────────────────────────────────────────

    /** The task's people. For a deleted task, those assigned when it was deleted, kept in its audit entry. */
    private record TaskContext(String title, boolean deleted, Long creatorId, Set<Long> assignees, Set<Long> roles,
                               Set<Long> commenters, Set<Long> followers) {

        Set<Long> related() {
            Set<Long> related = new LinkedHashSet<>(assignees);
            if (creatorId != null) {
                related.add(creatorId);
            }
            related.addAll(commenters);
            related.addAll(followers);
            return related;
        }
    }

    private TaskContext taskContext(Long taskId, List<Entry> entries) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT title, created_by FROM tasks WHERE task_id = ?", taskId);
        Set<Long> commenters = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM task_comments WHERE task_id = ? AND deleted_at IS NULL", Long.class, taskId));
        Set<Long> followers = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT user_id FROM task_followers WHERE task_id = ?", Long.class, taskId));
        if (!rows.isEmpty()) {
            return new TaskContext((String) rows.get(0).get("title"), false,
                    ((Number) rows.get(0).get("created_by")).longValue(),
                    new LinkedHashSet<>(jdbcTemplate.queryForList(
                            "SELECT user_id FROM task_assignments WHERE task_id = ?", Long.class, taskId)),
                    new LinkedHashSet<>(jdbcTemplate.queryForList(
                            "SELECT role_id FROM task_role_assignments WHERE task_id = ?", Long.class, taskId)),
                    commenters, followers);
        }
        // Deleted: what its deletion recorded.
        Map<String, Object> snapshot = entries.stream()
                .filter(entry -> "TASK_DELETED".equals(entry.log().action()))
                .map(entry -> AuditClassifier.snapshotOf(entry.log().changes()))
                .findFirst().orElse(Map.of());
        Set<Long> assignees = new LinkedHashSet<>();
        if (snapshot.get("_assigneeIds") instanceof Collection<?> ids) {
            ids.stream().map(AuditClassifier::longOf).filter(Objects::nonNull).forEach(assignees::add);
        }
        Object title = snapshot.get("title");
        return new TaskContext(title == null ? null : String.valueOf(title), true,
                AuditClassifier.longOf(snapshot.get("createdBy")), assignees, Set.of(), Set.of(), Set.of());
    }

    private Names names(Long boardId) {
        return new Names(
                nameMap("SELECT column_id AS id, name FROM columns WHERE board_id = ?", boardId),
                nameMap("SELECT label_id AS id, name FROM labels WHERE board_id = ?", boardId),
                nameMap("SELECT priority_id AS id, name FROM board_priorities WHERE board_id = ?", boardId));
    }

    private Map<String, String> nameMap(String sql, Long boardId) {
        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            names.put(String.valueOf(rs.getLong("id")), rs.getString("name"));
        }, boardId);
        return names;
    }

    private boolean botPresent(Long serverId) {
        List<Boolean> present = jdbcTemplate.queryForList(
                "SELECT bot_present FROM servers WHERE server_id = ?", Boolean.class, serverId);
        return !present.isEmpty() && Boolean.TRUE.equals(present.get(0));
    }
}
