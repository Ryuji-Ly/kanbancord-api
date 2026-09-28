package com.kanbancord_api.boardpost;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.permission.BoardAudienceQuery;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Posting a board in a Discord channel, done through the bot as the user who ran the command. Posting
 * shows the board to everyone who can see the channel, so it needs the right to manage the board's
 * details, like renaming it.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/posts")
public class BoardPostController {

    static final String PERMISSION = "EDIT_BOARD_DETAILS";
    /** Enough for the largest channels the bot is likely to see; more is refused rather than slow. */
    private static final int MAX_AUDIENCE = 25_000;
    /** How many of the people who cannot see the board are named in the answer. */
    private static final int NAMED = 10;

    private final BoardPostService posts;
    private final BoardAudienceQuery audience;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public BoardPostController(BoardPostService posts, BoardAudienceQuery audience, Authorizer authorizer,
                               ResourceValidator resourceValidator) {
        this.posts = posts;
        this.audience = audience;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    /** Ids are strings: Discord ids do not fit in a JavaScript number. */
    public record PostRequest(@NotBlank String channelId, @NotBlank String messageId) {
    }

    public record PostResponse(@JsonSerialize(using = ToStringSerializer.class) Long postId) {
    }

    public record AudienceRequest(@NotNull @Size(max = MAX_AUDIENCE) List<@NotBlank String> userIds) {
    }

    /**
     * @param checked         how many people were asked about
     * @param hidden          how many of them cannot see the board or its tasks
     * @param hiddenUserIds   the first few of those, to name them
     */
    public record AudienceResponse(int checked, int hidden, List<String> hiddenUserIds) {
    }

    @PostMapping
    public ResponseEntity<PostResponse> register(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody PostRequest request,
            @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, PERMISSION);
        long postId = posts.register(serverId, boardId, snowflake(request.channelId()), snowflake(request.messageId()),
                userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PostResponse(postId));
    }

    /**
     * Which of the people who can see a channel could not see this board on the website: posting it
     * there would show it to them. Asked before posting, so the bot can warn.
     */
    @PostMapping("/audience")
    public ResponseEntity<AudienceResponse> audience(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody AudienceRequest request,
            @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, PERMISSION);
        List<Long> ids = request.userIds().stream().map(BoardPostController::snowflake).distinct().toList();
        List<Long> lacking = audience.lacking(serverId, boardId, ids, BoardAudienceQuery.SEES_BOARD_POST);
        return ResponseEntity.ok(new AudienceResponse(ids.size(), lacking.size(),
                lacking.stream().limit(NAMED).map(String::valueOf).toList()));
    }

    private static long snowflake(String value) {
        try {
            long id = Long.parseLong(value.trim());
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Not a Discord id: " + value);
        }
    }
}
