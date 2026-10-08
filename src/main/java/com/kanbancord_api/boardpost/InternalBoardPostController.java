package com.kanbancord_api.boardpost;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.board.BoardSnapshotQuery;
import com.kanbancord_api.board.BoardSnapshotResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The bot's side of board posts: which to redraw, the boards to draw them from, and what happened.
 * Only the bot, with its internal token, may call these.
 */
@RestController
public class InternalBoardPostController {

    private static final String BOT_TOKEN_HEADER = "X-Internal-Bot-Token";
    private static final int MAX_CLAIM = 50;

    private final BoardPostService posts;
    private final BoardSnapshotQuery snapshots;
    private final Authorizer authorizer;

    public InternalBoardPostController(BoardPostService posts, BoardSnapshotQuery snapshots, Authorizer authorizer) {
        this.posts = posts;
        this.snapshots = snapshots;
        this.authorizer = authorizer;
    }

    /** A post to redraw. Ids are strings: Discord ids do not fit in a JavaScript number. */
    public record ClaimedPost(
            @JsonSerialize(using = ToStringSerializer.class) Long postId,
            @JsonSerialize(using = ToStringSerializer.class) Long serverId,
            @JsonSerialize(using = ToStringSerializer.class) Long boardId,
            @JsonSerialize(using = ToStringSerializer.class) Long channelId,
            @JsonSerialize(using = ToStringSerializer.class) Long messageId,
            boolean boardExists) {

        static ClaimedPost from(BoardPostService.Claimed claimed) {
            return new ClaimedPost(claimed.postId(), claimed.serverId(), claimed.boardId(), claimed.channelId(),
                    claimed.messageId(), claimed.boardExists());
        }
    }

    /**
     * {@code blocked}: the bot may no longer edit the post there (it lost View Channel, say). It is
     * tried again now and then, never given up on, and the server is told once (see the answer).
     */
    public record Report(List<String> done, List<String> gone, List<String> retry, List<String> blocked) {
    }

    /** Blocked posts the server has not been told about yet: the bot tells its managers. */
    public record ReportAnswer(List<BlockedPost> tell) {
    }

    public record BlockedPost(
            @JsonSerialize(using = ToStringSerializer.class) Long postId,
            @JsonSerialize(using = ToStringSerializer.class) Long serverId,
            @JsonSerialize(using = ToStringSerializer.class) Long channelId,
            String boardName) {
    }

    /** Posts to redraw now; an empty list means none. Report each with {@code /report}. */
    @PostMapping("/api/internal/board-posts/claim")
    public ResponseEntity<List<ClaimedPost>> claim(
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @RequestParam(defaultValue = "20") int limit) {
        authorizer.requireInternalSyncAccess(botToken);
        return ResponseEntity.ok(posts.claim(Math.max(1, Math.min(limit, MAX_CLAIM))).stream()
                .map(ClaimedPost::from)
                .toList());
    }

    /** The whole board, as a post shows it. */
    @GetMapping("/api/internal/board-posts/snapshot")
    public ResponseEntity<BoardSnapshotResponse> snapshot(
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @RequestParam Long serverId,
            @RequestParam Long boardId) {
        authorizer.requireInternalSyncAccess(botToken);
        return ResponseEntity.ok(snapshots.loadComplete(serverId, boardId));
    }

    @PostMapping("/api/internal/board-posts/report")
    public ResponseEntity<ReportAnswer> report(
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @RequestBody Report report) {
        authorizer.requireInternalSyncAccess(botToken);
        posts.report(ids(report.done()), ids(report.gone()), ids(report.retry()));
        return ResponseEntity.ok(new ReportAnswer(posts.block(ids(report.blocked())).stream()
                .map(post -> new BlockedPost(post.postId(), post.serverId(), post.channelId(), post.boardName()))
                .toList()));
    }

    private static List<Long> ids(List<String> values) {
        return values == null ? List.of() : values.stream().map(Long::valueOf).toList();
    }
}
