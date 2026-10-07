package com.kanbancord_api.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who follows which task: followers hear about it by direct message, like the people assigned to it. */
@Service
public class TaskFollowService {

    private final JdbcTemplate jdbcTemplate;

    public TaskFollowService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Following a task one already follows changes nothing. */
    @Transactional
    public void follow(Long taskId, Long userId) {
        jdbcTemplate.update("INSERT INTO task_followers (task_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                taskId, userId);
    }

    /** Unfollowing a task one does not follow is not an error. */
    @Transactional
    public void unfollow(Long taskId, Long userId) {
        jdbcTemplate.update("DELETE FROM task_followers WHERE task_id = ? AND user_id = ?", taskId, userId);
    }
}
