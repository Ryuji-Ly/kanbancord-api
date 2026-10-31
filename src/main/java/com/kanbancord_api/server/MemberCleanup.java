package com.kanbancord_api.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Once a day, removes what we no longer need to keep about people:
 *
 * <ul>
 *   <li>With lazy member storage, members who never used KanbanCord in a server and that nothing there refers to
 *       (no assignment, follow, permission rule or reminder times on its boards). They are looked up in Discord
 *       again if they are ever needed.</li>
 *   <li>Users who are in no server with the bot and have nothing that shows their name: no boards, tasks,
 *       assignments, comments, uploads, audit log entries, permission rules or board posts, and no sign-in to the
 *       website. Their notifications, follows and settings go with them.</li>
 * </ul>
 *
 * Rows younger than a day are left alone, so someone just picked or just synced is not removed before they are
 * used. Both instances of the API may run this; removing what is already gone does nothing.
 */
@Component
public class MemberCleanup {

    private static final Logger log = LoggerFactory.getLogger(MemberCleanup.class);
    static final Duration GRACE = Duration.ofDays(1);

    private final JdbcTemplate jdbcTemplate;
    private final MemberDirectory memberDirectory;

    public MemberCleanup(JdbcTemplate jdbcTemplate, MemberDirectory memberDirectory) {
        this.jdbcTemplate = jdbcTemplate;
        this.memberDirectory = memberDirectory;
    }

    @Scheduled(fixedDelayString = "${kanbancord.members.cleanup-ms:86400000}",
            initialDelayString = "${kanbancord.members.cleanup-first-ms:600000}")
    public void run() {
        int members = memberDirectory.lazy() ? removeUnusedMembers(GRACE) : 0;
        int users = removeOrphanedUsers(GRACE);
        if (members > 0 || users > 0) {
            log.info("Member cleanup: removed {} unused members and {} orphaned users", members, users);
        }
    }

    /** @return how many members were removed */
    @Transactional
    public int removeUnusedMembers(Duration grace) {
        return jdbcTemplate.update("""
                DELETE FROM server_members sm
                USING servers s
                WHERE s.server_id = sm.server_id
                  AND sm.last_used_at IS NULL
                  AND sm.user_id <> s.owner_id
                  AND sm.updated_at < now() - make_interval(secs => ?)
                  AND NOT EXISTS (SELECT 1 FROM task_assignments a JOIN tasks t ON t.task_id = a.task_id
                                  JOIN boards b ON b.board_id = t.board_id
                                  WHERE a.user_id = sm.user_id AND b.server_id = sm.server_id)
                  AND NOT EXISTS (SELECT 1 FROM task_followers f JOIN tasks t ON t.task_id = f.task_id
                                  JOIN boards b ON b.board_id = t.board_id
                                  WHERE f.user_id = sm.user_id AND b.server_id = sm.server_id)
                  AND NOT EXISTS (SELECT 1 FROM permissions p
                                  WHERE p.subject_type = 'USER' AND p.subject_id = sm.user_id
                                    AND ((p.scope_type = 'SERVER' AND p.scope_id = sm.server_id)
                                      OR (p.scope_type = 'BOARD' AND p.scope_id IN (
                                          SELECT board_id FROM boards WHERE server_id = sm.server_id))))
                  AND NOT EXISTS (SELECT 1 FROM user_reminder_times r JOIN boards b ON b.board_id = r.board_id
                                  WHERE r.user_id = sm.user_id AND b.server_id = sm.server_id)
                """, grace.toSeconds());
    }

    /** @return how many users were removed */
    @Transactional
    public int removeOrphanedUsers(Duration grace) {
        return jdbcTemplate.update("""
                DELETE FROM users u
                WHERE u.created_at < now() - make_interval(secs => ?)
                  AND NOT EXISTS (SELECT 1 FROM server_members sm JOIN servers s ON s.server_id = sm.server_id
                                  WHERE sm.user_id = u.user_id AND s.bot_present)
                  AND NOT EXISTS (SELECT 1 FROM servers s WHERE s.owner_id = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM boards b WHERE b.created_by = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.created_by = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM task_assignments a WHERE a.user_id = u.user_id OR a.assigned_by = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM task_role_assignments a WHERE a.assigned_by = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM task_comments c WHERE c.user_id = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM task_comment_edits e WHERE e.editor_user_id = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM media_uploads m WHERE m.user_id = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.user_id = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM permissions p WHERE p.subject_type = 'USER' AND p.subject_id = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM board_posts bp WHERE bp.created_by = u.user_id)
                  AND NOT EXISTS (SELECT 1 FROM user_sessions s WHERE s.user_id = u.user_id)
                """, grace.toSeconds());
    }
}
