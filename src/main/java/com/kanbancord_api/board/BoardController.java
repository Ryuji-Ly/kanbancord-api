package com.kanbancord_api.board;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/servers/{serverId}/boards")
@Validated
public class BoardController {

    private final BoardService boardService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final PermissionEvaluationService permissionEvaluationService;
    private final BoardCommands boardCommands;
    private final BoardSnapshotQuery boardSnapshotQuery;

    public BoardController(
            BoardService boardService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            PermissionEvaluationService permissionEvaluationService,
            BoardCommands boardCommands,
            BoardSnapshotQuery boardSnapshotQuery) {
        this.boardService = boardService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.permissionEvaluationService = permissionEvaluationService;
        this.boardCommands = boardCommands;
        this.boardSnapshotQuery = boardSnapshotQuery;
    }

    /**
     * Create a new board in a server
     */
    @PostMapping
    public ResponseEntity<BoardResponse> createBoard(
            @PathVariable Long serverId,
            @Valid @RequestBody BoardRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(boardCommands.create(serverId, userId, request));
    }

    /**
     * Get all boards in a server
     */
    @GetMapping
    public ResponseEntity<Page<BoardResponse>> getAllBoards(
            @PathVariable Long serverId,
            @RequestParam(required = false) Boolean archived,
            @CurrentUser Long userId,
            Pageable pageable) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");

        // Visibility is evaluated per board (board-level overrides can hide a board), so filter the
        // full list first and paginate afterwards. Servers hold few boards, so this stays cheap.
        Pageable unpaged = Pageable.unpaged(pageable.getSort());
        List<Board> boards = archived != null
                ? boardService.findByServerIdAndArchived(serverId, archived, unpaged).getContent()
                : boardService.findByServerId(serverId, unpaged).getContent();

        Set<Long> visibleBoardIds = permissionEvaluationService.filterAllowedBoards(
                serverId,
                boards.stream().map(Board::getBoardId).toList(),
                userId,
                "VIEW_BOARD");
        List<BoardResponse> visible = boards.stream()
                .filter(board -> visibleBoardIds.contains(board.getBoardId()))
                .map(BoardResponse::from)
                .toList();

        return ResponseEntity.ok(page(visible, pageable));
    }

    /**
     * Get a specific board by ID
     */
    @GetMapping("/{boardId}")
    public ResponseEntity<BoardResponse> getBoardById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");

        return ResponseEntity.ok(BoardResponse.from(board));
    }

    /**
     * The board with its columns, tasks, assignments and the caller's board permissions, in one request
     */
    @GetMapping("/{boardId}/snapshot")
    public ResponseEntity<BoardSnapshotResponse> getBoardSnapshot(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(boardSnapshotQuery.load(serverId, boardId, userId));
    }

    /**
     * Update a board
     */
    @PutMapping("/{boardId}")
    public ResponseEntity<BoardResponse> updateBoard(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody BoardRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(boardCommands.update(serverId, boardId, userId, request));
    }

    /**
     * Archive or restore a board
     */
    @PatchMapping("/{boardId}/archive")
    public ResponseEntity<BoardResponse> archiveBoard(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId,
            @RequestParam(defaultValue = "true") Boolean archived) {
        return ResponseEntity.ok(boardCommands.setArchived(serverId, boardId, userId, Boolean.TRUE.equals(archived)));
    }

    /**
     * Delete a board
     */
    @DeleteMapping("/{boardId}")
    public ResponseEntity<Void> deleteBoard(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {
        boardCommands.delete(serverId, boardId, userId);
        return ResponseEntity.noContent().build();
    }

    private static <T> Page<T> page(List<T> items, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(items, pageable, items.size());
        }
        int from = (int) Math.min(pageable.getOffset(), items.size());
        int to = Math.min(from + pageable.getPageSize(), items.size());
        return new PageImpl<>(items.subList(from, to), pageable, items.size());
    }
}
