package com.kanbancord_api.boardpost;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * The boards posted in a channel that the caller may add tasks to: where a task made from a message in
 * that channel most likely belongs.
 */
@RestController
public class ChannelBoardsController {

    private final BoardPostService posts;
    private final Authorizer authorizer;
    private final PermissionEvaluationService permissions;

    public ChannelBoardsController(BoardPostService posts, Authorizer authorizer, PermissionEvaluationService permissions) {
        this.posts = posts;
        this.authorizer = authorizer;
        this.permissions = permissions;
    }

    public record ChannelBoards(List<Long> boardIds) {
    }

    @GetMapping("/api/servers/{serverId}/channels/{channelId}/boards")
    public ResponseEntity<ChannelBoards> postedIn(@PathVariable Long serverId, @PathVariable Long channelId,
                                                  @CurrentUser Long userId) {
        authorizer.requireUserInServer(userId, serverId);
        List<Long> posted = posts.boardsPostedIn(serverId, channelId);
        Set<Long> allowed = permissions.filterAllowedBoards(serverId, posted, userId, "CREATE_TASK");
        return ResponseEntity.ok(new ChannelBoards(posted.stream().filter(allowed::contains).toList()));
    }
}
