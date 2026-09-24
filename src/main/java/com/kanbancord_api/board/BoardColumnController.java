package com.kanbancord_api.board;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
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
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ColumnCommands columnCommands;

    public BoardColumnController(
            BoardColumnService boardColumnService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ColumnCommands columnCommands) {
        this.boardColumnService = boardColumnService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.columnCommands = columnCommands;
    }

    @PostMapping
    public ResponseEntity<BoardColumnResponse> createColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody BoardColumnRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(columnCommands.create(serverId, boardId, userId, request));
    }

    @GetMapping
    public ResponseEntity<List<BoardColumnResponse>> getAllColumns(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");

        resourceValidator.requireBoardInServer(boardId, serverId);

        List<BoardColumn> columns = boardColumnService.findByBoardIdOrdered(boardId);
        List<BoardColumnResponse> responses = columns.stream()
                .map(BoardColumnResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{columnId}")
    public ResponseEntity<BoardColumnResponse> getColumnById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);

        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);

        return ResponseEntity.ok(BoardColumnResponse.from(column));
    }

    @PutMapping("/{columnId}")
    public ResponseEntity<BoardColumnResponse> updateColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @Valid @RequestBody BoardColumnRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(columnCommands.update(serverId, boardId, columnId, userId, request));
    }

    @PostMapping("/{columnId}/move")
    public ResponseEntity<BoardColumnResponse> moveColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @Valid @RequestBody ColumnMoveRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(columnCommands.move(serverId, boardId, columnId, userId, request));
    }

    @DeleteMapping("/{columnId}")
    public ResponseEntity<Void> deleteColumn(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long columnId,
            @CurrentUser Long userId) {
        columnCommands.delete(serverId, boardId, columnId, userId);
        return ResponseEntity.noContent().build();
    }
}
