package com.kanbancord_api.board;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Following a whole board: the follower hears about every task on it by direct message, as if they followed each
 * one, for the events they chose. Anyone who can see a board may follow it; following is personal, so it is not in
 * the audit log.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/follow")
public class BoardFollowController {

    private final JdbcTemplate jdbcTemplate;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public BoardFollowController(JdbcTemplate jdbcTemplate, Authorizer authorizer, ResourceValidator resourceValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    public record FollowResponse(boolean following) {
    }

    /** Following a board one already follows changes nothing. */
    @PutMapping
    public ResponseEntity<FollowResponse> follow(@PathVariable Long serverId, @PathVariable Long boardId,
                                                 @CurrentUser Long userId) {
        requireVisible(serverId, boardId, userId);
        jdbcTemplate.update("INSERT INTO board_followers (board_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                boardId, userId);
        return ResponseEntity.ok(new FollowResponse(true));
    }

    /** Unfollowing a board one does not follow is not an error. */
    @DeleteMapping
    public ResponseEntity<FollowResponse> unfollow(@PathVariable Long serverId, @PathVariable Long boardId,
                                                   @CurrentUser Long userId) {
        requireVisible(serverId, boardId, userId);
        jdbcTemplate.update("DELETE FROM board_followers WHERE board_id = ? AND user_id = ?", boardId, userId);
        return ResponseEntity.ok(new FollowResponse(false));
    }

    private void requireVisible(Long serverId, Long boardId, Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
    }
}
