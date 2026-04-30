package com.kanbancord_api.controller;

import com.kanbancord_api.dto.BoardColumnRequest;
import com.kanbancord_api.dto.BoardColumnResponse;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.ResourceValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/columns")
@Validated
public class BoardColumnController {

    private final BoardColumnService boardColumnService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public BoardColumnController(
            BoardColumnService boardColumnService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.boardColumnService = boardColumnService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @PostMapping
    public ResponseEntity<BoardColumnResponse> createColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody BoardColumnRequest request,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "CREATE_COLUMN");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);

        BoardColumn column = new BoardColumn();
        column.setBoard(board);
        column.setName(request.getName());
        column.setPosition(request.getPosition());
        column.setColor(request.getColor());
        column.setWipLimit(request.getWipLimit());

        BoardColumn created = boardColumnService.create(column);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<BoardColumnResponse>> getAllColumns(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        resourceValidator.requireBoardInServer(boardId, serverId);

        List<BoardColumn> columns = boardColumnService.findByBoardIdOrdered(boardId);
        List<BoardColumnResponse> responses = columns.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{columnId}")
    public ResponseEntity<BoardColumnResponse> getColumnById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);

        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);

        return ResponseEntity.ok(mapToResponse(column));
    }

    @PutMapping("/{columnId}")
    public ResponseEntity<BoardColumnResponse> updateColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @Valid @RequestBody BoardColumnRequest request,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "EDIT_COLUMN");
        if (request.getPosition() != null) {
            accessValidator.requireServerPermission(userId, serverId, "MOVE_COLUMN");
        }
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);

        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);

        column.setName(request.getName());
        if (request.getPosition() != null) {
            column.setPosition(request.getPosition());
        }
        if (request.getColor() != null) {
            column.setColor(request.getColor());
        }
        if (request.getWipLimit() != null) {
            column.setWipLimit(request.getWipLimit());
        }

        BoardColumn updated = boardColumnService.update(column);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    @DeleteMapping("/{columnId}")
    public ResponseEntity<Void> deleteColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "DELETE_COLUMN");
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);

        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);

        resourceValidator.validateColumnHasNoTasks(columnId);

        boardColumnService.deleteById(column.getColumnId());
        return ResponseEntity.noContent().build();
    }

    private BoardColumnResponse mapToResponse(BoardColumn column) {
        BoardColumnResponse response = new BoardColumnResponse();
        response.setColumnId(column.getColumnId());
        response.setBoardId(column.getBoard().getBoardId());
        response.setName(column.getName());
        response.setPosition(column.getPosition());
        response.setColor(column.getColor());
        response.setWipLimit(column.getWipLimit());
        response.setCreatedAt(column.getCreatedAt());
        response.setUpdatedAt(column.getUpdatedAt());
        return response;
    }
}
