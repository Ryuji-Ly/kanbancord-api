package com.kanbancord_api.controller;

import com.kanbancord_api.dto.BoardRequest;
import com.kanbancord_api.dto.BoardResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.User;
import com.kanbancord_api.realtime.RealtimeEventPublisher;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.BoardService;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.ServerService;
import com.kanbancord_api.service.UserService;
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
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final ServerService serverService;
    private final UserService userService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final PermissionEvaluationService permissionEvaluationService;

    public BoardController(
            BoardService boardService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            ServerService serverService,
            UserService userService,
            RealtimeEventPublisher realtimeEventPublisher,
            PermissionEvaluationService permissionEvaluationService) {
        this.boardService = boardService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.serverService = serverService;
        this.userService = userService;
        this.realtimeEventPublisher = realtimeEventPublisher;
        this.permissionEvaluationService = permissionEvaluationService;
    }

    /**
     * Create a new board in a server
     */
    @PostMapping
    public ResponseEntity<BoardResponse> createBoard(
            @PathVariable Long serverId,
            @Valid @RequestBody BoardRequest request,
            @CurrentUser Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "CREATE_BOARD");
        resourceValidator.validatePathMatchesRequestId("serverId", serverId, request.getServerId());

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        resourceValidator.validateBoardNameUnique(request.getName(), serverId, null);

        Board board = new Board();
        board.setServer(server);
        board.setName(request.getName());
        board.setDescription(request.getDescription());
        board.setIsArchived(false);

        // The creator is always the authenticated user; request.createdBy is ignored.
        User creator = userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
        board.setCreatedBy(creator);

        Board created = boardService.create(board, request.getColumnNames());
        BoardResponse response = mapToResponse(created);
        realtimeEventPublisher.publishToServerAndBoardTopics(
                serverId,
                created.getBoardId(),
                realtimeEventPublisher.newEvent(
                        "BOARD_CREATED",
                        "BOARD",
                        serverId,
                        created.getBoardId(),
                        "BOARD",
                        created.getBoardId(),
                        userId,
                        response));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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

        accessValidator.requireServerPermission(userId, serverId, "VIEW_SERVER");

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
                .map(this::mapToResponse)
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
        accessValidator.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");

        return ResponseEntity.ok(mapToResponse(board));
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

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        accessValidator.requireBoardPermission(userId, serverId, boardId, "EDIT_BOARD_DETAILS");
        resourceValidator.validatePathMatchesRequestId("serverId", serverId, request.getServerId());
        resourceValidator.validateBoardNameUnique(request.getName(), serverId, boardId);

        board.setName(request.getName());
        if (request.getDescription() != null) {
            board.setDescription(request.getDescription());
        }

        Board updated = boardService.update(board);
        BoardResponse response = mapToResponse(updated);
        realtimeEventPublisher.publishToServerAndBoardTopics(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "BOARD_UPDATED",
                        "BOARD",
                        serverId,
                        boardId,
                        "BOARD",
                        boardId,
                        userId,
                        response));
        return ResponseEntity.ok(response);
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

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        accessValidator.requireBoardPermission(userId, serverId, boardId, "ARCHIVE_BOARD");
        board.setIsArchived(Boolean.TRUE.equals(archived));

        Board updated = boardService.update(board);
        BoardResponse response = mapToResponse(updated);
        realtimeEventPublisher.publishToServerAndBoardTopics(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        Boolean.TRUE.equals(archived) ? "BOARD_ARCHIVED" : "BOARD_RESTORED",
                        "BOARD",
                        serverId,
                        boardId,
                        "BOARD",
                        boardId,
                        userId,
                        response));
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a board
     */
    @DeleteMapping("/{boardId}")
    public ResponseEntity<Void> deleteBoard(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        accessValidator.requireBoardPermission(userId, serverId, boardId, "DELETE_BOARD");
        BoardResponse response = mapToResponse(board);

        boardService.deleteById(board.getBoardId());
        realtimeEventPublisher.publishToServerAndBoardTopics(
                serverId,
                boardId,
                realtimeEventPublisher.newEvent(
                        "BOARD_DELETED",
                        "BOARD",
                        serverId,
                        boardId,
                        "BOARD",
                        boardId,
                        userId,
                        response));
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

    private BoardResponse mapToResponse(Board board) {
        BoardResponse response = new BoardResponse();
        response.setBoardId(board.getBoardId());
        response.setServerId(board.getServer().getServerId());
        response.setName(board.getName());
        response.setDescription(board.getDescription());
        response.setIsArchived(board.getIsArchived());
        if (board.getCreatedBy() != null) {
            response.setCreatedBy(board.getCreatedBy().getUserId());
        }
        response.setCreatedAt(board.getCreatedAt());
        response.setUpdatedAt(board.getUpdatedAt());
        return response;
    }
}
