package com.kanbancord_api.priority;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/priorities")
public class BoardPriorityController {

    private final BoardPriorityService boardPriorityService;
    private final PriorityCommands priorityCommands;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public BoardPriorityController(
            BoardPriorityService boardPriorityService,
            PriorityCommands priorityCommands,
            Authorizer authorizer,
            ResourceValidator resourceValidator) {
        this.boardPriorityService = boardPriorityService;
        this.priorityCommands = priorityCommands;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    /** The board's levels, most urgent first. */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<BoardPriorityResponse>> list(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        return ResponseEntity.ok(boardPriorityService.findByBoardId(boardId).stream()
                .map(BoardPriorityResponse::from)
                .toList());
    }

    @PostMapping
    public ResponseEntity<BoardPriorityResponse> create(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody BoardPriorityRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(priorityCommands.create(serverId, boardId, userId, request));
    }

    @PutMapping("/{priorityId}")
    public ResponseEntity<BoardPriorityResponse> update(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long priorityId,
            @Valid @RequestBody BoardPriorityRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(priorityCommands.update(serverId, boardId, priorityId, userId, request));
    }

    /** Returns the whole list in its new order. */
    @PostMapping("/{priorityId}/move")
    public ResponseEntity<List<BoardPriorityResponse>> move(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long priorityId,
            @Valid @RequestBody PriorityMoveRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(priorityCommands.move(serverId, boardId, priorityId, userId, request.index()));
    }

    @DeleteMapping("/{priorityId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long priorityId,
            @CurrentUser Long userId) {
        priorityCommands.delete(serverId, boardId, priorityId, userId);
        return ResponseEntity.noContent().build();
    }
}
