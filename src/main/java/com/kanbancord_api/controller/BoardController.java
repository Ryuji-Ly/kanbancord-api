package com.kanbancord_api.controller;

import com.kanbancord_api.dto.BoardRequest;
import com.kanbancord_api.dto.BoardResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.BoardService;
import com.kanbancord_api.service.BusinessValidationService;
import com.kanbancord_api.service.ServerAccessValidator;
import com.kanbancord_api.service.ServerService;
import com.kanbancord_api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}/boards")
@Validated
public class BoardController {

    private final BoardService boardService;
    private final ServerAccessValidator accessValidator;
    private final BusinessValidationService businessValidation;
    private final ServerService serverService;
    private final UserService userService;

    public BoardController(
            BoardService boardService,
            ServerAccessValidator accessValidator,
            BusinessValidationService businessValidation,
            ServerService serverService,
            UserService userService) {
        this.boardService = boardService;
        this.accessValidator = accessValidator;
        this.businessValidation = businessValidation;
        this.serverService = serverService;
        this.userService = userService;
    }

    /**
     * Create a new board in a server
     */
    @PostMapping
    public ResponseEntity<BoardResponse> createBoard(
            @PathVariable Long serverId,
            @Valid @RequestBody BoardRequest request,
            @RequestParam(required = false) Long userId) { // TODO: Get from auth context

        // TODO: Get userId from security context
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        // Validate board name is unique within the server
        businessValidation.validateBoardNameUnique(request.getName(), serverId, null);

        Board board = new Board();
        board.setServer(server);
        board.setName(request.getName());
        board.setDescription(request.getDescription());
        board.setIsArchived(false);

        if (request.getCreatedBy() != null) {
            User creator = userService.findById(request.getCreatedBy())
                    .orElseThrow(() -> new ResourceNotFoundException("User", "userId", request.getCreatedBy()));
            board.setCreatedBy(creator);
        }

        Board created = boardService.create(board);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(created));
    }

    /**
     * Get all boards in a server
     */
    @GetMapping
    public ResponseEntity<Page<BoardResponse>> getAllBoards(
            @PathVariable Long serverId,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) Long userId,
            Pageable pageable) { // TODO: Get from auth context

        // TODO: Get userId from security context
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Page<Board> boards;
        if (archived != null) {
            boards = boardService.findByServerIdAndArchived(serverId, archived, pageable);
        } else {
            boards = boardService.findByServerId(serverId, pageable);
        }

        Page<BoardResponse> responses = boards.map(this::mapToResponse);

        return ResponseEntity.ok(responses);
    }

    /**
     * Get a specific board by ID
     */
    @GetMapping("/{boardId}")
    public ResponseEntity<BoardResponse> getBoardById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam(required = false) Long userId) { // TODO: Get from auth context

        // TODO: Get userId from security context
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Board board = boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

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
            @RequestParam(required = false) Long userId) { // TODO: Get from auth context

        // TODO: Get userId from security context
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        // Validate board name is unique within the server (excluding current board)
        businessValidation.validateBoardNameUnique(request.getName(), serverId, boardId);

        Board board = boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

        board.setName(request.getName());
        if (request.getDescription() != null) {
            board.setDescription(request.getDescription());
        }

        Board updated = boardService.update(board);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    /**
     * Delete a board
     */
    @DeleteMapping("/{boardId}")
    public ResponseEntity<Void> deleteBoard(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam(required = false) Long userId) { // TODO: Get from auth context

        // TODO: Get userId from security context
        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Board board = boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

        boardService.deleteById(board.getBoardId());
        return ResponseEntity.noContent().build();
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
