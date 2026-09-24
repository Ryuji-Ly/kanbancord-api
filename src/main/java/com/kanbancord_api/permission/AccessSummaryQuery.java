package com.kanbancord_api.permission;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.board.BoardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * What the caller may do in a server and on each board they can see, so a client can show the right
 * controls without asking about every board separately.
 */
@Service
@Transactional(readOnly = true)
public class AccessSummaryQuery {

    private static final List<String> SERVER_KEYS = Arrays.stream(KanbanPermissionCatalog.values())
            .filter(KanbanPermissionCatalog::isServerScopeAllowed)
            .map(KanbanPermissionCatalog::getKey)
            .toList();
    private static final List<String> BOARD_KEYS = Arrays.stream(KanbanPermissionCatalog.values())
            .filter(KanbanPermissionCatalog::isBoardScopeAllowed)
            .map(KanbanPermissionCatalog::getKey)
            .toList();

    private final Authorizer authorizer;
    private final BoardService boardService;
    private final PermissionEvaluationService permissionEvaluationService;

    public AccessSummaryQuery(
            Authorizer authorizer,
            BoardService boardService,
            PermissionEvaluationService permissionEvaluationService) {
        this.authorizer = authorizer;
        this.boardService = boardService;
        this.permissionEvaluationService = permissionEvaluationService;
    }

    /** Server-scope keys, and board-scope keys for every board (archived too) the caller can view. */
    public PermissionEvaluationService.AccessSummary load(Long serverId, Long actorUserId) {
        authorizer.requireUserInServer(actorUserId, serverId);
        List<Long> boardIds = boardService.findByServerId(serverId).stream().map(Board::getBoardId).toList();
        return permissionEvaluationService.summarize(serverId, boardIds, actorUserId, SERVER_KEYS, BOARD_KEYS);
    }
}
