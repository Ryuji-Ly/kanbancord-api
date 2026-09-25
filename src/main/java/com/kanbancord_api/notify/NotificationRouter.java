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

    public NotificationRouter(AuditLogRepository auditLogRepository, NotificationSettingsService settings,
                              PermissionEvaluationService permissions, JdbcTemplate jdbcTemplate) {
        this.auditLogRepository = auditLogRepository;
        this.settings = settings;
        this.permissions = permissions;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record BoardRef(Long boardId, String name) {
    }

    /** The task the changes are about; {@code deleted} when it no longer exists. */
    public record TaskRef(Long taskId, String title, boolean deleted) {
    }

    /** Ids and names the entries refer to, so they can be shown by name. */
    public record Names(Map<String, String> columns, Map<String, String> labels, Map<String, String> priorities) {
    }

    /** A message to one channel. {@code kind} is FEED or AUDIT; the audit channel never mentions anyone. */
    public record ChannelDelivery(String channelId, String kind, List<Long> entryIds, List<String> mentionUserIds,
                                  List<String> mentionRoleIds) {
    }

    /** A direct message. {@code mode} UNLESS_PINGED leaves it to the bot to skip it if a channel already did. */
    public record DirectMessage(String userId, List<Long> entryIds, String mode) {
    }

    public record Plan(long batchId, String serverId, BoardRef board, TaskRef task, List<AuditLogResponse> entries,
                       Names names, List<ChannelDelivery> channels, List<DirectMessage> directMessages) {

        public boolean isEmpty() {
            return channels.isEmpty() && directMessages.isEmpty();
        }
    }

    /** An entry with what it means for notifications. */
    private record Entry(AuditLog log, Set<NotificationEvent> events, Long actorId, Long subjectId) {
    }

    @Transactional(readOnly = true)
    public Plan route(NotificationQueue.Batch batch) {
        Long serverId = batch.serverId();
        List<Entry> entries = auditLogRepository.findAllById(batch.auditIds()).stream()
                .sorted(Comparator.comparing(AuditLog::getLogId))
                .map(log -> new Entry(log, AuditClassifier.eventsOf(log.getAction(), log.getChanges()),
                        log.getUser() == null ? null : log.getUser().getUserId(),
                        AuditClassifier.subjectIdOf(log.getAction(), log.getChanges())))
                .toList();
        if (entries.isEmpty() || !botPresent(serverId)) {
            return empty(batch);
        }

        AuditLog first = entries.get(0).log();
        Long boardId = first.getBoard() == null ? null : first.getBoard().getBoardId();
        BoardRef board = boardId == null ? null : new BoardRef(boardId, first.getBoard().getName());
        Long taskId = AuditClassifier.taskIdOf(first.getEntityType(), first.getAction(), first.getEntityId(), first.getChanges());
        TaskContext task = taskId == null ? null : taskContext(taskId, entries);

        List<ChannelDelivery> channels = new ArrayList<>(feedDeliveries(serverId, boardId, entries, task));
        settings.auditChannel(serverId).ifPresent(channelId -> channels.add(new ChannelDelivery(String.valueOf(channelId),
                "AUDIT", entries.stream().map(entry -> entry.log().getLogId()).toList(), List.of(), List.of())));
        List<DirectMessage> directMessages = task == null || boardId == null
                ? List.of()
                : directMessages(serverId, boardId, entries, task);

        return new Plan(batch.batchId(), String.valueOf(serverId), board,
                task == null ? null : new TaskRef(taskId, task.title(), task.deleted()),
                entries.stream().map(entry -> AuditLogResponse.from(entry.log())).toList(),
                boardId == null ? new Names(Map.of(), Map.of(), Map.of()) : names(boardId),
                channels, directMessages);
    }

    private Plan empty(NotificationQueue.Batch batch) {
        return new Plan(batch.batchId(), String.valueOf(batch.serverId()), null, null, List.of(),
                new Names(Map.of(), Map.of(), Map.of()), List.of(), List.of());
    }

    // ── Feeds ────────────────────────────────────────────────────────────────

    /** Feeds sharing a channel are posted there once, with everything either of them wants. */
    private List<ChannelDelivery> feedDeliveries(Long serverId, Long boardId, List<Entry> entries, TaskContext task) {
        Map<Long, LinkedHashSet<Long>> entriesByChannel = new LinkedHashMap<>();
        Map<Long, LinkedHashSet<String>> usersByChannel = new LinkedHashMap<>();
        Map<Long, LinkedHashSet<String>> rolesByChannel = new LinkedHashMap<>();

        for (NotificationSettingsService.Feed feed : settings.feeds(serverId)) {
            if (boardId == null || !feed.covers(boardId)) {
                continue;
            }
            for (Entry entry : entries) {
                List<NotificationEvent> wanted = entry.events().stream().filter(feed::wants).toList();
                if (wanted.isEmpty()) {
                    continue;
                }
                entriesByChannel.computeIfAbsent(feed.channelId(), id -> new LinkedHashSet<>()).add(entry.log().getLogId());
                LinkedHashSet<String> users = usersByChannel.computeIfAbsent(feed.channelId(), id -> new LinkedHashSet<>());
                LinkedHashSet<String> roles = rolesByChannel.computeIfAbsent(feed.channelId(), id -> new LinkedHashSet<>());
                for (NotificationEvent event : wanted) {
                    if (!feed.mentions(event.category())) {
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
                List.copyOf(ids), List.copyOf(usersByChannel.get(channelId)), List.copyOf(rolesByChannel.get(channelId)))));
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
                    || (mine.includeCommented() && task.commenters().contains(userId));
            List<Long> ids = new ArrayList<>();
            for (Entry entry : entries) {
                if (userId.equals(entry.actorId())) {
                    continue;
                }
                boolean wanted = entry.events().stream().anyMatch(event -> mine.wants(serverId, event)
                        && (event.isAboutAssignee() ? userId.equals(entry.subjectId()) : related));
                if (wanted) {
                    ids.add(entry.log().getLogId());
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
                               Set<Long> commenters) {

        Set<Long> related() {
            Set<Long> related = new LinkedHashSet<>(assignees);
            if (creatorId != null) {
                related.add(creatorId);
            }
            related.addAll(commenters);
            return related;
        }
    }

    private TaskContext taskContext(Long taskId, List<Entry> entries) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT title, created_by FROM tasks WHERE task_id = ?", taskId);
        Set<Long> commenters = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM task_comments WHERE task_id = ? AND deleted_at IS NULL", Long.class, taskId));
        if (!rows.isEmpty()) {
            return new TaskContext((String) rows.get(0).get("title"), false,
                    ((Number) rows.get(0).get("created_by")).longValue(),
                    new LinkedHashSet<>(jdbcTemplate.queryForList(
                            "SELECT user_id FROM task_assignments WHERE task_id = ?", Long.class, taskId)),
                    new LinkedHashSet<>(jdbcTemplate.queryForList(
                            "SELECT role_id FROM task_role_assignments WHERE task_id = ?", Long.class, taskId)),
                    commenters);
        }
        // Deleted: what its deletion recorded.
        Map<String, Object> snapshot = entries.stream()
                .filter(entry -> "TASK_DELETED".equals(entry.log().getAction()))
                .map(entry -> AuditClassifier.snapshotOf(entry.log().getChanges()))
                .findFirst().orElse(Map.of());
        Set<Long> assignees = new LinkedHashSet<>();
        if (snapshot.get("_assigneeIds") instanceof Collection<?> ids) {
            ids.stream().map(AuditClassifier::longOf).filter(Objects::nonNull).forEach(assignees::add);
        }
        Object title = snapshot.get("title");
        return new TaskContext(title == null ? null : String.valueOf(title), true,
                AuditClassifier.longOf(snapshot.get("createdBy")), assignees, Set.of(), Set.of());
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
