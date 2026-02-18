package com.kanbancord_api.controller;

import com.kanbancord_api.dto.BoardColumnRequest;
import com.kanbancord_api.dto.BoardColumnResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.BoardService;
import com.kanbancord_api.service.BusinessValidationService;
import com.kanbancord_api.service.ServerAccessValidator;
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
    private final BoardService boardService;
    private final ServerAccessValidator accessValidator;
    private final BusinessValidationService businessValidation;

    public BoardColumnController(
            BoardColumnService boardColumnService,
            BoardService boardService,
            ServerAccessValidator accessValidator,
            BusinessValidationService businessValidation) {
        this.boardColumnService = boardColumnService;
        this.boardService = boardService;
        this.accessValidator = accessValidator;
        this.businessValidation = businessValidation;
    }

    @PostMapping
    public ResponseEntity<BoardColumnResponse> createColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody BoardColumnRequest request,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Board board = boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

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
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        // Validate board belongs to server
        boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

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
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        BoardColumn column = boardColumnService.findByIdAndServerId(columnId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", columnId));

        return ResponseEntity.ok(mapToResponse(column));
    }

    @PutMapping("/{columnId}")
    public ResponseEntity<BoardColumnResponse> updateColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @Valid @RequestBody BoardColumnRequest request,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        BoardColumn column = boardColumnService.findByIdAndServerId(columnId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", columnId));

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
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        BoardColumn column = boardColumnService.findByIdAndServerId(columnId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", columnId));

        // Validate column has no tasks before deletion
        businessValidation.validateColumnHasNoTasks(columnId);

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
