package com.kanbancord_api.guide;

import com.kanbancord_api.permission.PermissionEvaluationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * How far a server has come, for the ticks in the bot's /guide. Boards and tasks count only where the
 * person asking can see them; the rest is yes or no for the whole server.
 */
@Service
@Transactional(readOnly = true)
public class GuideProgressService {

    /** The columns a new board starts with. */
    private static final String DEFAULT_COLUMNS = "To Do|In Progress|Done";

    private final JdbcTemplate jdbcTemplate;
    private final PermissionEvaluationService permissions;

    public GuideProgressService(JdbcTemplate jdbcTemplate, PermissionEvaluationService permissions) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissions = permissions;
    }

    /**
     * @param features      some optional feature is on
     * @param boards        boards the person can see
     * @param columns       some board they can see has columns other than the ones it started with
     * @param tasks         some task they can see exists
     * @param taskDetails   some task they can see has people, a due date, a priority, a label or a comment
     * @param updates       the server has an update feed or a board post
     * @param notifications the person follows a task here, or has changed their notification settings
     */
    public record GuideProgress(boolean features, int boards, boolean columns, boolean tasks, boolean taskDetails,
                                boolean updates, boolean notifications) {
    }

    public GuideProgress progress(Long serverId, Long userId) {
        List<Long> all = jdbcTemplate.queryForList("SELECT board_id FROM boards WHERE server_id = ?", Long.class, serverId);
        Set<Long> visible = permissions.filterAllowedBoards(serverId, all, userId, "VIEW_BOARD");
        Set<Long> withTasks = permissions.filterAllowedBoards(serverId, visible, userId, "VIEW_TASK");

        boolean features = exists("SELECT 1 FROM server_features WHERE server_id = ?", serverId);
        boolean columns = !visible.isEmpty() && exists("""
                SELECT 1 FROM (
                    SELECT string_agg(name, '|' ORDER BY position) AS names FROM columns
                    WHERE board_id = ANY (?) GROUP BY board_id
                ) shaped WHERE names <> ?
                """, ids(visible), DEFAULT_COLUMNS);
        boolean tasks = !withTasks.isEmpty() && exists("SELECT 1 FROM tasks WHERE board_id = ANY (?)", ids(withTasks));
        boolean taskDetails = tasks && exists("""
                SELECT 1 FROM tasks t WHERE t.board_id = ANY (?) AND (
                    t.due_date IS NOT NULL OR t.priority_id IS NOT NULL
                    OR EXISTS (SELECT 1 FROM task_assignments a WHERE a.task_id = t.task_id)
                    OR EXISTS (SELECT 1 FROM task_role_assignments r WHERE r.task_id = t.task_id)
                    OR EXISTS (SELECT 1 FROM task_labels l WHERE l.task_id = t.task_id)
                    OR EXISTS (SELECT 1 FROM task_comments c WHERE c.task_id = t.task_id AND c.deleted_at IS NULL))
                """, ids(withTasks));
        boolean updates = exists("SELECT 1 FROM notification_feeds WHERE server_id = ?", serverId)
                || exists("SELECT 1 FROM board_posts WHERE server_id = ?", serverId);
        boolean notifications = exists("SELECT 1 FROM user_notification_settings WHERE user_id = ?", userId)
                || exists("""
                        SELECT 1 FROM task_followers f JOIN tasks t ON t.task_id = f.task_id
                        JOIN boards b ON b.board_id = t.board_id WHERE f.user_id = ? AND b.server_id = ?
                        """, userId, serverId);
        return new GuideProgress(features, visible.size(), columns, tasks, taskDetails, updates, notifications);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS (" + sql + ")", Boolean.class, args));
    }

    private Array ids(Collection<Long> ids) {
        return jdbcTemplate.execute((java.sql.Connection connection) -> connection.createArrayOf("bigint", ids.toArray()));
    }
}
