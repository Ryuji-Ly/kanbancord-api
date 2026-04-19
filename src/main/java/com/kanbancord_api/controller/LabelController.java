package com.kanbancord_api.controller;

import com.kanbancord_api.dto.LabelRequest;
import com.kanbancord_api.dto.LabelResponse;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.Label;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.LabelService;
import com.kanbancord_api.service.ResourceValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/labels")
@Validated
public class LabelController {

    private final LabelService labelService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public LabelController(
            LabelService labelService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.labelService = labelService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @PostMapping
    public ResponseEntity<LabelResponse> createLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody LabelRequest request,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "CREATE_LABEL");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());

        Board board = resourceValidator.requireBoardInServer(boardId, serverId);

        resourceValidator.validateLabelNameUnique(request.getName(), boardId, null);

        Label label = new Label();
        label.setBoard(board);
        label.setName(request.getName());
        label.setColor(request.getColor());

        Label created = labelService.create(label);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<LabelResponse>> getAllLabels(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        resourceValidator.requireBoardInServer(boardId, serverId);

        List<Label> labels = labelService.findByBoardId(boardId);
        List<LabelResponse> responses = labels.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{labelId}")
    public ResponseEntity<LabelResponse> getLabelById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateLabelBelongsToBoard(labelId, boardId);

        Label label = resourceValidator.requireLabelInServer(labelId, serverId);

        return ResponseEntity.ok(mapToResponse(label));
    }

    @PutMapping("/{labelId}")
    public ResponseEntity<LabelResponse> updateLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @Valid @RequestBody LabelRequest request,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "EDIT_LABEL");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateLabelBelongsToBoard(labelId, boardId);

        Label label = resourceValidator.requireLabelInServer(labelId, serverId);

        resourceValidator.validateLabelNameUnique(request.getName(), boardId, labelId);

        label.setName(request.getName());
        label.setColor(request.getColor());

        Label updated = labelService.update(label);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    @DeleteMapping("/{labelId}")
    public ResponseEntity<Void> deleteLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @RequestParam Long userId) {

        accessValidator.requireServerPermission(userId, serverId, "DELETE_LABEL");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateLabelBelongsToBoard(labelId, boardId);

        Label label = resourceValidator.requireLabelInServer(labelId, serverId);

        labelService.deleteById(label.getLabelId());
        return ResponseEntity.noContent().build();
    }

    private LabelResponse mapToResponse(Label label) {
        LabelResponse response = new LabelResponse();
        response.setLabelId(label.getLabelId());
        response.setBoardId(label.getBoard().getBoardId());
        response.setName(label.getName());
        response.setColor(label.getColor());
        return response;
    }
}
