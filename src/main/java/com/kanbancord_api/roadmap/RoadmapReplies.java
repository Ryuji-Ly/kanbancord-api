package com.kanbancord_api.roadmap;

import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.notify.NotificationEvent;
import com.kanbancord_api.notify.NotificationQueue;
import com.kanbancord_api.notify.NotificationSettingsService;
import com.kanbancord_api.permission.PermissionEvaluationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tells the people taking part in a roadmap issue (whoever reported it and whoever commented on it before) about a
 * new comment, by direct message. They are usually not members of the roadmap's server, so the usual direct
 * messages, which only go to people who can see the board, would never reach them. Anyone who can see the board
 * hears about it the usual way instead, never twice.
 */
@Component
public class RoadmapReplies {

    private final RoadmapService roadmap;
    private final NotificationQueue queue;
    private final NotificationSettingsService settings;
    private final PermissionEvaluationService permissions;
    private final JdbcTemplate jdbcTemplate;

    public RoadmapReplies(RoadmapService roadmap, NotificationQueue queue, NotificationSettingsService settings,
            PermissionEvaluationService permissions, JdbcTemplate jdbcTemplate) {
        this.roadmap = roadmap;
        this.queue = queue;
        this.settings = settings;
        this.permissions = permissions;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** For comments made on the roadmap and on the board itself alike. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onEvent(DomainEvent event) {
        if (event.type() != EventType.TASK_COMMENT_CREATED || !roadmap.isRoadmapBoard(event.boardId())) {
            return;
        }
        Long commentId = event.entityId();
        Set<Long> people = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT t.created_by FROM task_comments c JOIN tasks t ON t.task_id = c.task_id
                WHERE c.comment_id = ? AND t.created_by IS NOT NULL
                UNION
                SELECT earlier.user_id FROM task_comments c
                JOIN task_comments earlier ON earlier.task_id = c.task_id
                WHERE c.comment_id = ? AND earlier.comment_id <> c.comment_id AND earlier.deleted_at IS NULL
                """, Long.class, commentId, commentId));
        people.remove(event.actorUserId());

        Map<Long, NotificationSettingsService.UserSettings> preferences = settings.userSettings(people);
        for (Long userId : people) {
            boolean seesBoard = permissions.isAllowed(event.serverId(), event.boardId(), userId, "VIEW_BOARD");
            if (!seesBoard && preferences.get(userId).wants(event.serverId(), NotificationEvent.COMMENT_CREATED)) {
                queue.enqueueRoadmapReply(userId, commentId);
            }
        }
    }
}
